package org.projectbarry.pbosinstaller.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.projectbarry.pbosinstaller.BuildConfig
import org.projectbarry.pbosinstaller.device.DeviceInfo
import org.projectbarry.pbosinstaller.device.Devices
import org.projectbarry.pbosinstaller.device.TestedDevice
import org.projectbarry.pbosinstaller.release.ImageDownloader
import org.projectbarry.pbosinstaller.release.ImageRelease
import org.projectbarry.pbosinstaller.release.Releases
import org.projectbarry.pbosinstaller.report.DeviceReport
import org.projectbarry.pbosinstaller.report.ReportSender
import org.projectbarry.pbosinstaller.storage.SdBlock
import org.projectbarry.pbosinstaller.storage.SdCard
import org.projectbarry.pbosinstaller.storage.SdCardWatcher
import org.projectbarry.pbosinstaller.writer.CardWriter
import org.projectbarry.pbosinstaller.abl.AblKnown
import org.projectbarry.pbosinstaller.abl.AblScript
import org.projectbarry.pbosinstaller.abl.AblSetup

/** The "Send Device Report" button. */
sealed interface ReportState {
    data object Ready : ReportState
    data object Sending : ReportState
    data class Done(val result: ReportSender.Result) : ReportState
}

/**
 * Where the user is in the install. The app only moves forward when the user
 * taps a button; it checks the chip and model by itself (local, nothing
 * changes), and goes back to [Card] if the card is taken out.
 */
sealed interface Step {
    data object WrongChip : Step
    /** Waiting for the user to insert a card and tap Continue. */
    data object Card : Step
    data object Untested : Step
    /** Model checked; waiting for the user to tap "Let's Go!" (looks up the release). */
    data class Ready(val images: List<String>) : Step
    data object LoadingRelease : Step
    data class Offer(val image: ImageRelease, val needed: Long, val free: Long) : Step
    data class Downloading(val progress: ImageDownloader.Progress) : Step
    data class Verifying(val done: Long, val total: Long) : Step
    /** Downloaded and checked; waiting for "Write to SD Card". */
    data class Downloaded(val image: ImageRelease) : Step
    /** The write script is in Downloads; waiting for the user to run it as root. */
    data class RunScript(val tag: String, val launcher: String) : Step
    /** The script is running: [phase] is its state (started, checking, unmounting, writing). */
    data class Writing(val tag: String, val phase: String, val done: Long, val total: Long) : Step
    /** The script is reading the card back to check it. */
    data class CardCheck(val tag: String, val done: Long, val total: Long) : Step
    data class Written(val tag: String) : Step
    /** Boot menu: waiting for "Back Up Boot Loader". */
    data object BootMenu : Step
    /** A boot loader job ([job]) is saved in Downloads; waiting for the user to run it as root. */
    data class AblRunScript(val job: String, val launcher: String) : Step
    data class AblWorking(val job: String) : Step
    /** Backed up; [slotA]/[slotB] say what each slot holds. Waiting for the 5-tap confirm. */
    data class AblBackedUp(val slotA: String, val slotB: String, val documents: String?) : Step
    /** Waiting for "Install Boot Menu". */
    data object AblInstallReady : Step
    data object BootMenuDone : Step
    /** "Try again" goes back to [back], the step the user was on. */
    data class Failed(val message: String, val back: Step) : Step
}

class InstallerViewModel(app: Application) : AndroidViewModel(app) {
    val info: DeviceInfo = DeviceInfo.read()
    val tested: TestedDevice? = Devices.find(info)
    /** Full name with maker, e.g. "Retroid Pocket Nova" (Android's maker and model if untested). */
    val fullName: String = tested?.name ?: "${info.manufacturer} ${info.model}"
    /** Short name for a tested handheld ("Nova"), or "handheld" if unknown. */
    val handheld: String = tested?.shortName ?: "handheld"
    /** True when this build lets untested devices through (gradle -PallowUntested=true). */
    val untestedAllowed = BuildConfig.ALLOW_UNTESTED

    private val watcher = SdCardWatcher(app)
    val card: StateFlow<SdCard?> = watcher.card
    private val downloader = ImageDownloader(app)
    private val writer = CardWriter(app)
    /** The checked parts (file, SHA-256) and the image's SHA-256, from the last download. */
    private var checkedParts: List<Pair<java.io.File, String>>? = null
    private var imageSha: String? = null
    private var watchJob: Job? = null

    /** False in builds made without the relay address (see docs/HOW-IT-WORKS.md). */
    val reportAvailable = BuildConfig.REPORT_URL.isNotBlank()
    private val reporter = ReportSender(app, BuildConfig.REPORT_URL, BuildConfig.VERSION_NAME)
    private val _report = MutableStateFlow<ReportState>(
        if (reporter.sentRecently()) ReportState.Done(ReportSender.Result.SENT) else ReportState.Ready
    )
    val report: StateFlow<ReportState> = _report

    /** Exactly what "Send Device Report" would send, for the preview. */
    fun reportFields(): Map<String, String> =
        DeviceReport.build(info, BuildConfig.VERSION_NAME, SdBlock.find()?.name, DeviceReport.rootKind())

    fun sendReport(fields: Map<String, String>) {
        if (!reportAvailable) return
        if (_report.value != ReportState.Ready && _report.value != ReportState.Done(ReportSender.Result.FAILED)) return
        if (reporter.sentRecently()) {
            _report.value = ReportState.Done(ReportSender.Result.SENT)
            return
        }
        _report.value = ReportState.Sending
        viewModelScope.launch {
            _report.value = ReportState.Done(withContext(Dispatchers.IO) { reporter.send(fields) })
        }
    }

    private val _step = MutableStateFlow<Step>(if (info.soc == null) Step.WrongChip else Step.Card)
    val step: StateFlow<Step> = _step
    private var job: Job? = null

    init {
        if (info.soc != null) {
            watcher.start()
            viewModelScope.launch { watcher.card.collect { onCard(it) } }
            resumeWrite()
        }
    }

    /** A write still running from before the app was closed: show it again. */
    private fun resumeWrite() {
        val tag = writer.currentTag ?: return
        val status = writer.status() ?: return
        if (status.state == "done" || status.state == "failed") {
            writer.clear()
            return
        }
        _step.value = Step.Writing(tag, status.state, status.done, status.total)
        watchWrite(tag, back = Step.Card)
    }

    /** Taking the card out goes back to the card step; putting one in never moves on by itself. */
    private fun onCard(card: SdCard?) {
        val step = _step.value
        if (card == null && (step is Step.Ready || step is Step.LoadingRelease || step is Step.Offer)) {
            job?.cancel()
            _step.value = Step.Card
        }
    }

    /** "Continue" on the card step: checks the model (local) and shows the result. */
    fun continueWithCard() {
        val card = watcher.card.value
        if (_step.value != Step.Card || card == null || !card.bigEnough) return
        _step.value = when {
            tested != null -> Step.Ready(tested.images)
            untestedAllowed -> Step.Ready(Devices.imagesBySoc.getValue(info.soc!!))
            else -> Step.Untested
        }
    }

    /** "Let's Go!" on the Ready step: looks up the release, the first time the app goes online. */
    fun lookUpRelease() {
        val ready = _step.value as? Step.Ready ?: return
        _step.value = Step.LoadingRelease
        job?.cancel()
        job = viewModelScope.launch {
            _step.value = try {
                withContext(Dispatchers.IO) {
                    val (_, release) = Releases.fetchLatest(BuildConfig.RELEASES_REPO)
                    val image = Releases.pickImage(release, ready.images)
                        ?: return@withContext Step.Failed("The newest PB-OS release has no image for this device yet.", ready)
                    Step.Offer(image, downloader.bytesNeeded(image), downloader.freeBytes())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Step.Failed("Couldn't reach GitHub to find the newest PB-OS release (${e.message}). Check Wi-Fi and try again.", ready)
            }
        }
    }

    fun download(image: ImageRelease) {
        val offer = _step.value as? Step.Offer ?: return
        job?.cancel()
        job = viewModelScope.launch {
            _step.value = try {
                withContext(Dispatchers.IO) {
                    val margin = 256L shl 20
                    if (downloader.bytesNeeded(image) + margin > downloader.freeBytes()) {
                        throw ImageDownloader.DownloadError(
                            "Not enough free space on internal storage. Free up ${formatBytes(downloader.bytesNeeded(image) + margin - downloader.freeBytes())} and try again."
                        )
                    }
                    val sums = downloader.fetchVerifiedSums(image)
                    val files = downloader.download(image) { _step.value = Step.Downloading(it) }
                    downloader.verify(files, sums) { done, total -> _step.value = Step.Verifying(done, total) }
                    checkedParts = files.map { it to sums.getValue(it.name) }
                    imageSha = sums["pb-os-${image.tag}-${image.image}.img"]
                        ?: throw ImageDownloader.DownloadError("The release's checksum list has no entry for the unpacked image.")
                    Step.Downloaded(image)
                }
            } catch (e: ImageDownloader.DownloadError) {
                Step.Failed(e.message ?: "Download failed.", offer)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Step.Failed("Download failed (${e.message}). Try again.", offer)
            }
        }
    }

    /** "Write to SD Card": prepares the root script and asks the user to run it. */
    fun prepareWrite() {
        val downloaded = _step.value as? Step.Downloaded ?: return
        val parts = checkedParts ?: return
        val sha = imageSha ?: return
        val card = watcher.card.value
        if (card == null || !card.bigEnough) return
        viewModelScope.launch {
            _step.value = try {
                val tag = downloaded.image.tag
                val launcher = withContext(Dispatchers.IO) { writer.prepare(tag, parts, sha, card.sizeBytes) }
                watchWrite(tag, back = downloaded)
                if (launcher != null) {
                    Step.RunScript(tag, launcher)
                } else if (withContext(Dispatchers.IO) { writer.start() }) {
                    // The handheld lets the app run it as root itself: no detour.
                    Step.Writing(tag, "started", 0, 0)
                } else {
                    withContext(Dispatchers.IO) { writer.fallbackLauncher() }?.let { Step.RunScript(tag, it) }
                        ?: Step.Failed("Couldn't start the card write as root.", downloaded)
                }
            } catch (e: Exception) {
                Step.Failed("Couldn't prepare the card write (${e.message}).", downloaded)
            }
        }
    }

    /** Follows the root script through its status file until it finishes. */
    private fun watchWrite(tag: String, back: Step) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                val status = withContext(Dispatchers.IO) { writer.status() }
                if (status != null && status.state !in FINISHED && status.ageMs > STALE_MS) {
                    // The script updates the status every 2 s; silence for a minute means it was stopped.
                    _step.value = Step.Failed(
                        "Writing stopped before it finished (the handheld may have restarted Android). " +
                            "The card is not ready. Write it again.",
                        back,
                    )
                    writer.clear()
                    return@launch
                }
                if (status != null) {
                    when (status.state) {
                        "verifying" -> _step.value = Step.CardCheck(tag, status.done, status.total)
                        "done" -> { _step.value = Step.Written(tag); writer.clear(); return@launch }
                        "failed" -> {
                            _step.value = Step.Failed(status.message.ifBlank { "Writing the card failed." }, back)
                            writer.clear()
                            return@launch
                        }
                        else -> _step.value = Step.Writing(tag, status.state, status.done, status.total)
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private companion object {
        val FINISHED = setOf("done", "failed")
        const val STALE_MS = 60_000L
    }

    private val abl: AblSetup? = info.soc?.let { AblSetup(app, info, it, tested?.name ?: "${info.manufacturer} ${info.model}") }
    private var ablWatch: Job? = null

    /** "Set Up Boot Menu" after the card is written. */
    fun openBootMenu() { _step.value = Step.BootMenu }

    /** "Back Up Boot Loader": copies both ABL slots (changes nothing). */
    fun backUpBootLoader() = runAbl(AblScript.BACKUP, Step.BootMenu) { it.prepareBackup() }

    /** The 5-tap confirm after the backup was saved somewhere else. */
    fun backupConfirmed() { _step.value = Step.AblInstallReady }

    /** "Install Boot Menu". */
    fun installBootMenu() = runAbl(AblScript.INSTALL, Step.AblInstallReady) { it.prepareInstall() }

    fun saveBackupZip(uri: android.net.Uri, onDone: (Boolean) -> Unit) {
        val setup = abl ?: return
        viewModelScope.launch {
            onDone(withContext(Dispatchers.IO) { runCatching { setup.writeZip(uri) }.isSuccess })
        }
    }

    fun backupZipName(): String = (abl?.backupName() ?: "PB-OS ABL backup") + ".zip"

    private fun runAbl(job: String, back: Step, prepare: (AblSetup) -> String?) {
        val setup = abl ?: return
        viewModelScope.launch {
            _step.value = try {
                val launcher = withContext(Dispatchers.IO) { prepare(setup) }
                watchAbl(setup, job, back)
                when {
                    launcher != null -> Step.AblRunScript(job, launcher)
                    withContext(Dispatchers.IO) { setup.start(job) } -> Step.AblWorking(job)
                    else -> withContext(Dispatchers.IO) { setup.fallbackLauncher(job) }?.let { Step.AblRunScript(job, it) }
                        ?: Step.Failed("Couldn't start the boot loader step as root.", back)
                }
            } catch (e: Exception) {
                Step.Failed("Couldn't prepare the boot loader step (${e.message}).", back)
            }
        }
    }

    private fun watchAbl(setup: AblSetup, job: String, back: Step) {
        ablWatch?.cancel()
        ablWatch = viewModelScope.launch {
            while (true) {
                val status = withContext(Dispatchers.IO) { setup.status(job) }
                if (status != null && status.state !in FINISHED && status.ageMs > STALE_MS) {
                    _step.value = Step.Failed("The boot loader step stopped before it finished. Nothing more was changed; try again.", back)
                    setup.clear(job)
                    return@launch
                }
                when (status?.state) {
                    null -> {}
                    "done" -> {
                        setup.clear(job)
                        _step.value = if (job == AblScript.BACKUP) {
                            val a = status.values["slot_a"].orEmpty()
                            val b = status.values["slot_b"].orEmpty()
                            val documents = withContext(Dispatchers.IO) { runCatching { setup.copyToDocuments() }.getOrNull() }
                            if (AblKnown.alreadyInstalled(a, b)) Step.BootMenuDone else Step.AblBackedUp(a, b, documents)
                        } else {
                            Step.BootMenuDone
                        }
                        return@launch
                    }
                    "failed" -> {
                        setup.clear(job)
                        _step.value = Step.Failed(status.message.ifBlank { "The boot loader step failed." }, back)
                        return@launch
                    }
                    else -> if (_step.value is Step.AblRunScript) _step.value = Step.AblWorking(job)
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /** Opens Retroid's handheld settings, where "Run Script as Root" is; Android settings elsewhere. */
    fun handheldSettingsIntent(): android.content.Intent =
        if (isRetroid) {
            android.content.Intent().setClassName("com.rp.settings", "com.ro.settings.activity.MainSettingsActivity")
        } else {
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS)
        }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Retroid handhelds run root scripts from Handheld Settings → Advanced → Run Script as Root. */
    val isRetroid: Boolean = info.manufacturer.equals("Moorechip", ignoreCase = true)

    /** "Try again": back to the step the user was on (the card step if the card is gone). */
    fun retry() {
        val failed = _step.value as? Step.Failed ?: return
        job?.cancel()
        val needsCard = failed.back is Step.Ready || failed.back is Step.Offer || failed.back is Step.Downloaded
        _step.value = if (needsCard && watcher.card.value == null) Step.Card else failed.back
    }

    override fun onCleared() {
        if (info.soc != null) watcher.stop()
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1_000_000.0)
}
