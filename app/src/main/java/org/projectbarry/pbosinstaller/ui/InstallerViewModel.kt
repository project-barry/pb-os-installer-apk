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
import org.projectbarry.pbosinstaller.release.UpdateRelease
import org.projectbarry.pbosinstaller.release.Updates
import org.projectbarry.pbosinstaller.root.FirstUpdate
import org.projectbarry.pbosinstaller.root.RootJobs
import org.projectbarry.pbosinstaller.report.DeviceReport
import org.projectbarry.pbosinstaller.report.ReportSender
import org.projectbarry.pbosinstaller.storage.SdBlock
import org.projectbarry.pbosinstaller.storage.SdCard
import org.projectbarry.pbosinstaller.storage.SdCardWatcher
import org.projectbarry.pbosinstaller.writer.CardWriter
import org.projectbarry.pbosinstaller.abl.AblKnown
import org.projectbarry.pbosinstaller.abl.AblScript
import org.projectbarry.pbosinstaller.abl.AblSetup
import org.projectbarry.pbosinstaller.internal.InternalInstaller
import org.projectbarry.pbosinstaller.internal.InternalLayout
import org.projectbarry.pbosinstaller.internal.InternalScript

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
    /**
     * [update]: the newest patch, downloaded too and installed on the first start;
     * null when there is none, or [updateUnknown] when GitHub couldn't be asked.
     */
    data class Offer(
        val image: ImageRelease,
        val needed: Long,
        val free: Long,
        val update: UpdateRelease? = null,
        val updateUnknown: Boolean = false,
    ) : Step
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
    /** [firstUpdate]: the patch PB-OS installs on its first start, or null; [updateFailed] when it couldn't go on. */
    data class Written(val tag: String, val firstUpdate: String? = null, val updateFailed: Boolean = false) : Step
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
    /** Internal storage: the read-only check is running. */
    data object InternalChecking : Step
    /** An internal storage job ([job]) is saved in Downloads; waiting for the user to run it as root. */
    data class InternalRunScript(val job: String, val launcher: String) : Step
    /** PB-OS or ArmadaOS found with room for PB-OS; waiting for Continue. */
    data class InternalFound(val plan: InternalLayout.Plan) : Step
    /** Nothing the app can install over (or no room); [reason] says why. */
    data class InternalRefused(val reason: String) : Step
    /** The install script is running: [phase] is its state (checking, writing, verifying, boot, home, system…). */
    data class InternalWriting(val tag: String, val phase: String, val done: Long, val total: Long) : Step
    data class InternalDone(val tag: String, val firstUpdate: String? = null, val updateFailed: Boolean = false) : Step
    /** "Try again" goes back to [back], the step the user was on. */
    data class Failed(val message: String, val back: Step) : Step
}

/** A newer version of this app, from the same tap that looks up PB-OS. */
sealed interface AppUpdateState {
    data class Available(val release: org.projectbarry.pbosinstaller.update.AppRelease) : AppUpdateState
    data class Downloading(val release: org.projectbarry.pbosinstaller.update.AppRelease, val done: Long, val total: Long) : AppUpdateState
    /** Downloaded; Android must first allow this app to install apps. */
    data class NeedsPermission(val release: org.projectbarry.pbosinstaller.update.AppRelease, val apk: java.io.File) : AppUpdateState
    /** Handed to Android, which asks the user and then replaces the app. */
    data class Installing(val release: org.projectbarry.pbosinstaller.update.AppRelease, val apk: java.io.File) : AppUpdateState
    data class Failed(val release: org.projectbarry.pbosinstaller.update.AppRelease, val message: String) : AppUpdateState
}

/** Tools → Repair PB-OS. */
sealed interface RepairState {
    data object Menu : RepairState
    data object Checking : RepairState
    /** A repair job ([job]) is saved in Downloads; waiting for the user to run it as root. */
    data class RunScript(val job: String, val launcher: String) : RepairState
    data class Report(val report: org.projectbarry.pbosinstaller.repair.RepairReport) : RepairState
    data class Fixing(val fix: org.projectbarry.pbosinstaller.repair.RepairReport.Fix) : RepairState
    data class Fixed(val message: String) : RepairState
    data class Failed(val message: String) : RepairState
}

/** Where PB-OS goes: the microSD card, or over a Linux install on internal storage. */
enum class Target { Card, Internal }

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
    private val updateDownloader = ImageDownloader(app, "updates")
    private val appUpdater = org.projectbarry.pbosinstaller.update.AppUpdate.Updater(app)
    private val repairer = info.soc?.let { org.projectbarry.pbosinstaller.repair.Repairer(app, it) }
    /** The Tools screen replaces the install steps while open; the install keeps its place. */
    private val _tools = MutableStateFlow(false)
    val tools: StateFlow<Boolean> = _tools
    private val _repair = MutableStateFlow<RepairState>(RepairState.Menu)
    val repair: StateFlow<RepairState> = _repair
    private var repairWatch: Job? = null
    private val _appUpdate = MutableStateFlow<AppUpdateState?>(null)
    val appUpdate: StateFlow<AppUpdateState?> = _appUpdate
    /** The patch that goes on with the image, once downloaded and checked. */
    private var firstUpdate: FirstUpdate.Files? = null
    private val writer = CardWriter(app)
    private val internal = InternalInstaller(app)
    /** Card or internal storage, chosen on the first step. */
    var target: Target = Target.Card
        private set
    /** What the internal check found, and the plan the install follows. */
    private var internalProbe: InternalInstaller.Probe? = null
    var internalPlan: InternalLayout.Plan? = null
        private set
    /** Over PB-OS: keep its HOME (games, saves, Steam login) and replace only boot and system. */
    var keepHome: Boolean = false
        private set
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
        watchAppUpdate()
        if (info.soc != null) {
            watcher.start()
            viewModelScope.launch { watcher.card.collect { onCard(it) } }
            resumeWrite()
        }
    }

    /** A write still running from before the app was closed: show it again. */
    private fun resumeWrite() {
        internal.currentTag?.let { tag ->
            val status = internal.status()
            if (status == null || status.state in FINISHED) {
                internal.clear()
            } else {
                target = Target.Internal
                _step.value = Step.InternalWriting(tag, status.state, status.done, status.total)
                watchInternal(tag, back = Step.Card)
                return
            }
        }
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
        if (target == Target.Card && card == null && (step is Step.Ready || step is Step.LoadingRelease || step is Step.Offer)) {
            job?.cancel()
            _step.value = Step.Card
        }
    }

    /** "Continue" on the card step: checks the model (local) and shows the result. */
    fun continueWithCard() {
        val card = watcher.card.value
        if (_step.value != Step.Card || card == null || !card.bigEnough) return
        target = Target.Card
        _step.value = modelChecked()
    }

    /** The model check (local): Ready for a tested handheld, Untested otherwise. */
    private fun modelChecked(): Step = when {
        tested != null -> Step.Ready(tested.images)
        untestedAllowed -> Step.Ready(Devices.imagesBySoc.getValue(info.soc!!))
        else -> Step.Untested
    }

    /** "Install Over Linux on Internal Storage": reads the internal partition table as root (changes nothing). */
    fun checkInternal() {
        if (_step.value != Step.Card) return
        internalProbe = null
        internalPlan = null
        _step.value = Step.InternalChecking
        viewModelScope.launch {
            _step.value = try {
                val launcher = withContext(Dispatchers.IO) { internal.prepareProbe() }
                watchProbe()
                when {
                    launcher != null -> Step.InternalRunScript(InternalScript.PROBE, launcher)
                    withContext(Dispatchers.IO) { internal.startProbe() } -> Step.InternalChecking
                    else -> withContext(Dispatchers.IO) { internal.probeFallback() }
                        ?.let { Step.InternalRunScript(InternalScript.PROBE, it) }
                        ?: Step.Failed("Couldn't check internal storage as root.", Step.Card)
                }
            } catch (e: Exception) {
                Step.Failed("Couldn't prepare the internal storage check (${e.message}).", Step.Card)
            }
        }
    }

    private fun watchProbe() {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                val status = withContext(Dispatchers.IO) { internal.probeStatus() }
                when (status?.state) {
                    null -> {}
                    "done" -> {
                        _step.value = withContext(Dispatchers.IO) {
                            try {
                                val probe = internal.readProbe(status)
                                when (val r = InternalLayout.inspect(probe.gpt)) {
                                    is InternalLayout.Result.Refused -> Step.InternalRefused(r.reason)
                                    is InternalLayout.Result.Found -> {
                                        val busy = internal.busyInLinuxSpace(probe, r.plan)
                                        if (busy.isNotEmpty()) {
                                            Step.InternalRefused(
                                                "Android is using part of the ${r.plan.kind.label} space " +
                                                    "(partition ${busy.joinToString()}). Restart the handheld and try again."
                                            )
                                        } else {
                                            internalProbe = probe
                                            internalPlan = r.plan
                                            Step.InternalFound(r.plan)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Step.InternalRefused("Couldn't read the internal partition table (${e.message}). Nothing was changed.")
                            }
                        }
                        internal.clearProbe()
                        return@launch
                    }
                    "failed" -> {
                        _step.value = Step.Failed(status.message.ifBlank { "Checking internal storage failed." }, Step.Card)
                        internal.clearProbe()
                        return@launch
                    }
                    else -> if (status.ageMs > STALE_MS) {
                        _step.value = Step.Failed("Checking internal storage stopped before it finished. Try again.", Step.Card)
                        internal.clearProbe()
                        return@launch
                    } else if (_step.value is Step.InternalRunScript) {
                        _step.value = Step.InternalChecking
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /**
     * "Continue" after internal storage was checked: the model check, then the same
     * download as for a card. [keep] keeps an existing PB-OS install's HOME.
     */
    fun continueInternal(keep: Boolean) {
        val found = _step.value as? Step.InternalFound ?: return
        target = Target.Internal
        keepHome = keep && found.plan.kind == InternalLayout.Kind.PbOs
        _step.value = modelChecked()
    }

    /** "Back" from the internal storage result. */
    fun backToStart() {
        watchJob?.cancel()
        target = Target.Card
        _step.value = Step.Card
    }

    /** "Let's Go!" on the Ready step: looks up the release, the first time the app goes online. */
    fun lookUpRelease() {
        val ready = _step.value as? Step.Ready ?: return
        _step.value = Step.LoadingRelease
        job?.cancel()
        // The app's own newer version, looked up on the same tap (never blocks the install).
        if (_appUpdate.value == null) viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { org.projectbarry.pbosinstaller.update.AppUpdate.findNewer(BuildConfig.VERSION_NAME) }.getOrNull()
            }?.let { _appUpdate.value = AppUpdateState.Available(it) }
        }
        job = viewModelScope.launch {
            _step.value = try {
                withContext(Dispatchers.IO) {
                    val (_, release) = Releases.fetchLatest(BuildConfig.RELEASES_REPO)
                    val image = Releases.pickImage(release, ready.images)
                        ?: return@withContext Step.Failed("The newest PB-OS release has no image for this device yet.", ready)
                    // The newest patch goes on too, so PB-OS doesn't have to update right away.
                    // Not finding out is no reason to stop: PB-OS can update itself later.
                    val update = runCatching { Updates.findPatch(image.tag, image.image) }
                    Step.Offer(
                        image,
                        downloader.bytesNeeded(image) + (update.getOrNull()?.let { updateDownloader.bytesNeeded(it.parts) } ?: 0),
                        downloader.freeBytes(),
                        update.getOrNull(),
                        updateUnknown = update.isFailure,
                    )
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
                    if (offer.needed + margin > downloader.freeBytes()) {
                        throw ImageDownloader.DownloadError(
                            "Not enough free space on internal storage. Free up ${formatBytes(offer.needed + margin - downloader.freeBytes())} and try again."
                        )
                    }
                    val sums = downloader.fetchVerifiedSums(image)
                    val files = downloader.download(image) { _step.value = Step.Downloading(it) }
                    downloader.verify(files, sums) { done, total -> _step.value = Step.Verifying(done, total) }
                    checkedParts = files.map { it to sums.getValue(it.name) }
                    imageSha = sums["pb-os-${image.tag}-${image.image}.img"]
                        ?: throw ImageDownloader.DownloadError("The release's checksum list has no entry for the unpacked image.")
                    firstUpdate = offer.update?.let { u ->
                        val signed = updateDownloader.fetchSigned(u.sums, u.sumsSig)
                        val parts = updateDownloader.download(u.parts) { _step.value = Step.Downloading(it) }
                        updateDownloader.verifyJoined(parts, u.packageName, signed.hashes) { done, total -> _step.value = Step.Verifying(done, total) }
                        val files = parts + updateDownloader.saveSums(signed)
                        FirstUpdate.Files(u.tag, files.map { RootJobs.rootPath(it) to sha256(it) })
                    }
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

    /** "Install PB-OS" over internal storage (after the 5-tap confirm). */
    fun prepareInternalInstall() {
        val downloaded = _step.value as? Step.Downloaded ?: return
        val parts = checkedParts ?: return
        val sha = imageSha ?: return
        val probe = internalProbe ?: return
        val plan = internalPlan ?: return
        if (target != Target.Internal) return
        viewModelScope.launch {
            _step.value = try {
                val tag = downloaded.image.tag
                val launcher = withContext(Dispatchers.IO) {
                    internal.prepareInstall(tag, probe, plan, writer.sevenZip, parts, sha, keepHome, firstUpdate)
                }
                watchInternal(tag, back = downloaded)
                when {
                    launcher != null -> Step.InternalRunScript(InternalScript.INSTALL, launcher)
                    withContext(Dispatchers.IO) { internal.start() } -> Step.InternalWriting(tag, "started", 0, 0)
                    else -> withContext(Dispatchers.IO) { internal.fallbackLauncher() }
                        ?.let { Step.InternalRunScript(InternalScript.INSTALL, it) }
                        ?: Step.Failed("Couldn't start the install as root.", downloaded)
                }
            } catch (e: Exception) {
                Step.Failed("Couldn't prepare the install (${e.message}).", downloaded)
            }
        }
    }

    private fun watchInternal(tag: String, back: Step) {
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            while (true) {
                val status = withContext(Dispatchers.IO) { internal.status() }
                if (status != null && status.state !in FINISHED && status.ageMs > STALE_MS) {
                    _step.value = Step.Failed(
                        "Installing stopped before it finished (the handheld may have restarted Android). " +
                            "Android is fine, but PB-OS isn't installed. Install it again.",
                        back,
                    )
                    internal.clear()
                    return@launch
                }
                when (status?.state) {
                    null -> {}
                    "done" -> {
                        _step.value = Step.InternalDone(tag, firstUpdate?.tag, status.values["first_update"] == "failed")
                        internal.clear()
                        return@launch
                    }
                    "failed" -> {
                        _step.value = Step.Failed(status.message.ifBlank { "Installing PB-OS failed." }, back)
                        internal.clear()
                        return@launch
                    }
                    else -> _step.value = Step.InternalWriting(tag, status.state, status.done, status.total)
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    /** "Write to SD Card": prepares the root script and asks the user to run it. */
    fun prepareWrite() {
        val downloaded = _step.value as? Step.Downloaded ?: return
        if (target != Target.Card) return
        val parts = checkedParts ?: return
        val sha = imageSha ?: return
        val card = watcher.card.value
        if (card == null || !card.bigEnough) return
        viewModelScope.launch {
            _step.value = try {
                val tag = downloaded.image.tag
                val launcher = withContext(Dispatchers.IO) { writer.prepare(tag, parts, sha, card.sizeBytes, firstUpdate) }
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
                        "done" -> {
                            _step.value = Step.Written(tag, firstUpdate?.tag, status.values["first_update"] == "failed")
                            writer.clear()
                            return@launch
                        }
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

    /** "Update App": downloads and checks the new version, then hands it to Android's installer. */
    fun updateApp() {
        val release = when (val s = _appUpdate.value) {
            is AppUpdateState.Available -> s.release
            is AppUpdateState.Failed -> s.release
            is AppUpdateState.NeedsPermission -> return installApp(s.release, s.apk)
            is AppUpdateState.Installing -> return installApp(s.release, s.apk)
            else -> return
        }
        viewModelScope.launch {
            _appUpdate.value = AppUpdateState.Downloading(release, 0, release.apk.size)
            _appUpdate.value = try {
                val apk = withContext(Dispatchers.IO) {
                    appUpdater.download(release) { done, total -> _appUpdate.value = AppUpdateState.Downloading(release, done, total) }
                }
                if (appUpdater.allowed()) installNow(release, apk) else AppUpdateState.NeedsPermission(release, apk)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                AppUpdateState.Failed(release, e.message ?: "The app update failed.")
            }
        }
    }

    private fun installApp(release: org.projectbarry.pbosinstaller.update.AppRelease, apk: java.io.File) {
        if (!appUpdater.allowed()) {
            runCatching { getApplication<Application>().startActivity(appUpdater.allowIntent()) }
            return
        }
        viewModelScope.launch { _appUpdate.value = installNow(release, apk) }
    }

    private suspend fun installNow(release: org.projectbarry.pbosinstaller.update.AppRelease, apk: java.io.File): AppUpdateState =
        try {
            withContext(Dispatchers.IO) { appUpdater.install(apk) }
            AppUpdateState.Installing(release, apk)
        } catch (e: Exception) {
            AppUpdateState.Failed(release, "Android couldn't start the update (${e.message}).")
        }

    /** Android's installer said no (or the user cancelled): show it, keep the button. */
    private fun watchAppUpdate() = viewModelScope.launch {
        org.projectbarry.pbosinstaller.update.AppUpdate.result.collect { r ->
            val s = _appUpdate.value
            if (r is org.projectbarry.pbosinstaller.update.AppUpdate.Result.Failed && s is AppUpdateState.Installing) {
                _appUpdate.value = AppUpdateState.Failed(s.release, r.message)
            }
        }
    }

    fun openTools() { _tools.value = true }

    fun closeTools() {
        _tools.value = false
        val r = _repair.value
        if (r is RepairState.Report || r is RepairState.Fixed || r is RepairState.Failed) _repair.value = RepairState.Menu
    }

    /** "Check PB-OS": the read-only repair check (root). */
    fun checkPbOs() {
        val r = repairer ?: return
        if (_repair.value is RepairState.Checking || _repair.value is RepairState.Fixing) return
        _repair.value = RepairState.Checking
        runRepairJob(org.projectbarry.pbosinstaller.repair.RepairScript.CHECK, RepairState.Checking, { r.prepareCheck() }) { status ->
            RepairState.Report(r.readCheck(status))
        }
    }

    /** A fix from the report; the boot menu goes through the installer's own steps. */
    fun applyFix(fix: org.projectbarry.pbosinstaller.repair.RepairReport.Fix) {
        val r = repairer ?: return
        when (fix) {
            org.projectbarry.pbosinstaller.repair.RepairReport.Fix.BootMenu -> {
                val step = _step.value
                val busy = step is Step.Writing || step is Step.CardCheck || step is Step.InternalWriting ||
                    step is Step.AblWorking || step is Step.RunScript || step is Step.AblRunScript || step is Step.InternalRunScript
                if (busy) {
                    _repair.value = RepairState.Failed("The installer is busy. Let it finish, then try again.")
                    return
                }
                _tools.value = false
                _repair.value = RepairState.Menu
                _step.value = Step.BootMenu
            }
            is org.projectbarry.pbosinstaller.repair.RepairReport.Fix.UndoUpdate,
            is org.projectbarry.pbosinstaller.repair.RepairReport.Fix.Repoint,
            is org.projectbarry.pbosinstaller.repair.RepairReport.Fix.Undo -> {
                val (install, kernel) = when (fix) {
                    is org.projectbarry.pbosinstaller.repair.RepairReport.Fix.UndoUpdate -> fix.install to fix.kernel
                    is org.projectbarry.pbosinstaller.repair.RepairReport.Fix.Repoint -> fix.install to fix.kernel
                    is org.projectbarry.pbosinstaller.repair.RepairReport.Fix.Undo -> fix.install to fix.kernel
                    else -> return
                }
                _repair.value = RepairState.Fixing(fix)
                val undo = (fix as? org.projectbarry.pbosinstaller.repair.RepairReport.Fix.UndoUpdate)?.update
                runRepairJob(org.projectbarry.pbosinstaller.repair.RepairScript.KERNEL, RepairState.Fixing(fix), { r.prepareKernel(install, kernel, undo?.id) }) {
                    RepairState.Fixed(
                        if (undo != null) {
                            "Done: on its next start, PB-OS's updater undoes ${undo.version ?: "the update"} and restarts once " +
                                "more. Start PB-OS now; don't turn it off while it shows it is restoring the previous version."
                        } else {
                            "Done: ${install.title.substringBefore(" (")} has the new kernel; the old one is kept as KERNEL.bad on " +
                                "its boot partition. Restart into PB-OS to try it."
                        }
                    )
                }
            }
        }
    }

    /** Prepares and starts a repair job, shows [running] while it runs, and turns "done" into [onDone]. */
    private fun runRepairJob(
        job: String,
        running: RepairState,
        prepare: () -> String?,
        onDone: (org.projectbarry.pbosinstaller.root.RootJobs.Status) -> RepairState,
    ) {
        val r = repairer ?: return
        repairWatch?.cancel()
        repairWatch = viewModelScope.launch {
            try {
                val launcher = withContext(Dispatchers.IO) { prepare() }
                if (launcher != null) {
                    _repair.value = RepairState.RunScript(job, launcher)
                } else if (!withContext(Dispatchers.IO) { r.start(job) }) {
                    val fallback = withContext(Dispatchers.IO) { r.fallbackLauncher(job) }
                    if (fallback == null) {
                        _repair.value = RepairState.Failed("Couldn't start the repair step as root.")
                        return@launch
                    }
                    _repair.value = RepairState.RunScript(job, fallback)
                }
            } catch (e: Exception) {
                _repair.value = RepairState.Failed("Couldn't prepare the repair step (${e.message}).")
                return@launch
            }
            while (true) {
                val status = withContext(Dispatchers.IO) { r.status(job) }
                when (status?.state) {
                    null -> {}
                    "done" -> {
                        _repair.value = try {
                            withContext(Dispatchers.IO) { onDone(status) }
                        } catch (e: Exception) {
                            RepairState.Failed("Couldn't read what the repair step found (${e.message}).")
                        }
                        r.clear(job)
                        return@launch
                    }
                    "failed" -> {
                        _repair.value = RepairState.Failed(status.message.ifBlank { "The repair step failed." })
                        r.clear(job)
                        return@launch
                    }
                    else -> {
                        if (status.ageMs > STALE_MS) {
                            _repair.value = RepairState.Failed("The repair step stopped before it finished. Try again.")
                            r.clear(job)
                            return@launch
                        }
                        if (_repair.value is RepairState.RunScript) _repair.value = running
                    }
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
        val needsCard = target == Target.Card &&
            (failed.back is Step.Ready || failed.back is Step.Offer || failed.back is Step.Downloaded)
        _step.value = if (needsCard && watcher.card.value == null) Step.Card else failed.back
    }

    override fun onCleared() {
        if (info.soc != null) watcher.stop()
    }
}

private fun sha256(file: java.io.File): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(1 shl 20)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1_000_000.0)
}
