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

/** The "Send Device Report" button. */
sealed interface ReportState {
    data object Ready : ReportState
    data object Sending : ReportState
    data class Done(val result: ReportSender.Result) : ReportState
}

/** Where the user is in the install, in the order the app checks things. */
sealed interface Step {
    data object Checking : Step
    data object WrongChip : Step
    data object NeedCard : Step
    data object Untested : Step
    data object LoadingRelease : Step
    data class Offer(val image: ImageRelease, val needed: Long, val free: Long) : Step
    data class Downloading(val progress: ImageDownloader.Progress) : Step
    data class Verifying(val done: Long, val total: Long) : Step
    data class Downloaded(val image: ImageRelease) : Step
    data class Failed(val message: String) : Step
}

class InstallerViewModel(app: Application) : AndroidViewModel(app) {
    val info: DeviceInfo = DeviceInfo.read()
    val tested: TestedDevice? = Devices.find(info)
    /** True when this build lets untested devices through (gradle -PallowUntested=true). */
    val untestedAllowed = BuildConfig.ALLOW_UNTESTED

    private val watcher = SdCardWatcher(app)
    val card: StateFlow<SdCard?> = watcher.card
    private val downloader = ImageDownloader(app)

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

    private val _step = MutableStateFlow<Step>(Step.Checking)
    val step: StateFlow<Step> = _step
    private var job: Job? = null

    init {
        if (info.soc == null) {
            _step.value = Step.WrongChip
        } else {
            watcher.start()
            viewModelScope.launch { watcher.card.collect { onCard(it) } }
        }
    }

    private fun onCard(card: SdCard?) {
        val step = _step.value
        // Once the download has started a missing card doesn't matter until flashing.
        val beforeDownload = step is Step.Checking || step is Step.NeedCard || step is Step.Untested ||
            step is Step.LoadingRelease || step is Step.Offer
        if (!beforeDownload) return
        if (card == null) {
            job?.cancel()
            _step.value = Step.NeedCard
        } else if (step is Step.Checking || step is Step.NeedCard) {
            checkDevice()
        }
    }

    private fun checkDevice() {
        val images = when {
            tested != null -> tested.images
            untestedAllowed -> Devices.imagesBySoc.getValue(info.soc!!)
            else -> {
                _step.value = Step.Untested
                return
            }
        }
        loadRelease(images)
    }

    private fun loadRelease(images: List<String>) {
        _step.value = Step.LoadingRelease
        job?.cancel()
        job = viewModelScope.launch {
            _step.value = try {
                withContext(Dispatchers.IO) {
                    val (_, release) = Releases.fetchLatest(BuildConfig.RELEASES_REPO)
                    val image = Releases.pickImage(release, images)
                        ?: return@withContext Step.Failed("The newest PB-OS release has no image for this device yet.")
                    Step.Offer(image, downloader.bytesNeeded(image), downloader.freeBytes())
                }
            } catch (e: Exception) {
                Step.Failed("Couldn't reach GitHub to find the newest PB-OS release (${e.message}). Check Wi-Fi and try again.")
            }
        }
    }

    fun download(image: ImageRelease) {
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
                    Step.Downloaded(image)
                }
            } catch (e: ImageDownloader.DownloadError) {
                Step.Failed(e.message ?: "Download failed.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Step.Failed("Download failed (${e.message}). Try again.")
            }
        }
    }

    /** Start over from the device checks. */
    fun retry() {
        job?.cancel()
        if (info.soc == null) return
        _step.value = Step.Checking
        watcher.refresh()
        onCard(watcher.card.value)
    }

    override fun onCleared() {
        if (info.soc != null) watcher.stop()
    }
}

fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1_000_000.0)
}
