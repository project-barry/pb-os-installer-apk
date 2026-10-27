package org.projectbarry.pbosinstaller.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.projectbarry.pbosinstaller.BuildConfig
import org.projectbarry.pbosinstaller.R
import org.projectbarry.pbosinstaller.abl.AblKnown
import org.projectbarry.pbosinstaller.abl.AblScript
import org.projectbarry.pbosinstaller.device.Devices
import org.projectbarry.pbosinstaller.internal.InternalLayout
import org.projectbarry.pbosinstaller.internal.InternalScript
import org.projectbarry.pbosinstaller.repair.RepairReport
import org.projectbarry.pbosinstaller.repair.RepairScript
import org.projectbarry.pbosinstaller.report.DeviceReport
import org.projectbarry.pbosinstaller.report.ReportSender
import org.projectbarry.pbosinstaller.storage.SdCard

@Composable
fun InstallerScreen(vm: InstallerViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    val tools by vm.tools.collectAsStateWithLifecycle()
    val repair by vm.repair.collectAsStateWithLifecycle()
    // Screen stays on while a root job runs, so the progress stays in view (the
    // job itself also keeps the handheld awake, see ScriptKit).
    val busy = step is Step.Writing || step is Step.CardCheck || step is Step.AblWorking ||
        step is Step.RunScript || step is Step.AblRunScript || step is Step.InternalChecking ||
        step is Step.InternalRunScript || step is Step.InternalWriting ||
        repair is RepairState.Checking || repair is RepairState.Fixing || repair is RepairState.RunScript
    val view = LocalView.current
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    // A new scroll position for each orientation: rotating starts the page from the
    // top, in the same frame as the new layout (no scroll animation afterwards).
    val orientation = LocalConfiguration.current.orientation
    val scroll = remember(orientation) { ScrollState(0) }

    // Laid out from the window's current size, so it fits any screen shape,
    // rotation or split-screen: side by side when wide, one column otherwise.
    // The cards scroll together as one page; only the footer stays on screen.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = if (maxWidth < 400.dp || maxHeight < 400.dp) 12.dp else 20.dp
        val wide = maxWidth >= 560.dp && maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                // The device card (with Send Device Report) belongs to the first
                // screens only; after Continue the step card has the page to itself.
                val showDevice = !tools && (step is Step.Card || step is Step.Untested || step is Step.WrongChip)
                Header()
                AppUpdateBanner(vm)
                if (tools) {
                    ToolsCard(repair, vm, if (wide) Modifier.widthIn(max = 600.dp) else Modifier.fillMaxWidth())
                } else if (wide && showDevice) {
                    // Side by side: tops aligned, the shorter card stretched to the taller one.
                    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        StepCard(step, card, vm, Modifier.weight(1.4f).fillMaxHeight())
                        DeviceCard(vm, Modifier.weight(1f).fillMaxHeight())
                    }
                } else if (wide) {
                    // On its own, the step card is only as wide as its contents (capped so
                    // long text still wraps into readable lines).
                    StepCard(step, card, vm, Modifier.widthIn(max = 600.dp))
                } else {
                    StepCard(step, card, vm, Modifier.fillMaxWidth())
                    if (showDevice) DeviceCard(vm)
                }
            }
            Footer(vm, gap)
        }
    }
}

// Tighter than Material's defaults, so the footer fits two lines on narrow screens.
private val LinkPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
private val FooterButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * The footer, always on screen: app version and links on the left, the two
 * buttons about the install on the right (on a second line when it's narrow).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Footer(vm: InstallerViewModel, gap: Dp) {
    var page by remember { mutableStateOf<String?>(null) }
    when (page) {
        "about" -> AboutDialog(onClose = { page = null })
        "report" -> ReportInfoDialog(
            report = remember { DeviceReport.text(vm.reportFields()) },
            onClose = { page = null },
        )
        "licenses" -> LicensesDialog(onClose = { page = null })
        "github" -> GitHubDialog(onClose = { page = null })
        "discord" -> DiscordDialog(onClose = { page = null })
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = gap - 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Each group wraps whole links and buttons onto the next line on narrow
        // screens; their labels never break mid-word. The small gaps leave room for
        // the focus outline.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "PB-OS Installer ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                softWrap = false,
                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp, end = 4.dp),
            )
            TextButton(onClick = { page = "github" }, contentPadding = LinkPadding, modifier = Modifier.focusRing()) { Text("GitHub", softWrap = false) }
            TextButton(onClick = { page = "discord" }, contentPadding = LinkPadding, modifier = Modifier.focusRing()) { Text("Discord", softWrap = false) }
            TextButton(onClick = { page = "licenses" }, contentPadding = LinkPadding, modifier = Modifier.focusRing()) { Text("Licenses", softWrap = false) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = { page = "about" }, contentPadding = FooterButtonPadding, modifier = Modifier.focusRing()) {
                Text("How Does it Work?", softWrap = false)
            }
            OutlinedButton(onClick = { page = "report" }, contentPadding = FooterButtonPadding, modifier = Modifier.focusRing()) {
                Text("What's in the Report?", softWrap = false)
            }
            val tools by vm.tools.collectAsStateWithLifecycle()
            OutlinedButton(
                onClick = { if (tools) vm.closeTools() else vm.openTools() },
                contentPadding = FooterButtonPadding,
                modifier = Modifier.focusRing(),
            ) {
                Text(if (tools) "Back to Installer" else "Tools", softWrap = false)
            }
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The Project Barry logo, as on the org's GitHub page.
        Image(
            bitmap = ImageBitmap.imageResource(R.drawable.project_barry_logo),
            contentDescription = "Project Barry logo",
            filterQuality = FilterQuality.High,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
        )
        Spacer(Modifier.size(12.dp))
        Text("PB-OS Installer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StepCard(step: Step, card: SdCard?, vm: InstallerViewModel, modifier: Modifier = Modifier) {
    // With a controller, each new step's main control is selected at once, so the
    // selection doesn't fall into the device panel when the old button disappears.
    val primary = remember { FocusRequester() }
    val inputMode = LocalInputModeManager.current.inputMode
    LaunchedEffect(step::class) {
        if (inputMode == InputMode.Keyboard) {
            delay(50)
            runCatching { primary.requestFocus() }
        }
    }
    Card(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                Step.LoadingRelease -> Busy("Looking for the newest PB-OS release…")

                Step.WrongChip -> {
                    Title("This device can't run PB-OS")
                    Body(
                        "PB-OS only runs on handhelds with a Snapdragon 8 Gen 2 (SM8550) or " +
                            "Snapdragon 8 Gen 3 (SM8650) chip. This one reports \"${vm.info.socModel.ifBlank { "unknown" }}\"."
                    )
                }

                Step.Card -> CardStep(card, vm, primary)

                is Step.Ready -> {
                    Title(if (vm.tested != null) "Good news! Your ${vm.handheld} is supported." else "Untested handheld")
                    if (vm.tested == null) {
                        Warning("UNTESTED DEVICE: this build allows installing on handhelds PB-OS was never tested on.")
                    }
                    when {
                        vm.tested == null -> {}
                        vm.tested.hardwareTested -> Body("PB-OS has been tested on the ${vm.fullName}.")
                        else -> {
                            Warning("NOT TESTED YET: PB-OS has support for the ${vm.fullName}, but nobody has run it on one yet.")
                            Body("Expect problems, and please tell us on Discord how it goes.")
                        }
                    }
                    Body("Tap the button below to get started.")
                    Button(onClick = vm::lookUpRelease, modifier = Modifier.focusRing().focusRequester(primary)) {
                        Text("Let's Go!")
                    }
                }

                Step.Untested -> {
                    Title("Not tested on this handheld yet")
                    Body(
                        "This device has the right chip, a ${vm.info.soc?.label}, but PB-OS hasn't been tested " +
                            "on it, so this app won't install it. PB-OS is tested on: " +
                            Devices.tested.joinToString(", ") { it.name } + "."
                    )
                    Body(
                        "Got one of those and still see this? Tap " +
                            (if (vm.reportAvailable) "\"Send Device Report\" below" else "\"What's in the Report?\" below, copy the report and send it to us on Discord") +
                            ", so we can add your device."
                    )
                }

                is Step.Offer -> OfferStep(step, card, vm, primary)

                is Step.Downloading -> {
                    Title("Downloading PB-OS")
                    val p = step.progress
                    Progress(p.done, p.total)
                    Body("Part ${p.part} of ${p.parts} · ${formatBytes(p.done)} of ${formatBytes(p.total)}")
                    Body("The download will continue in the background.")
                }

                is Step.Verifying -> {
                    Title("Checking the download")
                    Progress(step.done, step.total)
                }

                is Step.Downloaded ->
                    if (vm.target == Target.Internal) InternalInstallStep(step, vm, primary) else WriteStep(step, card, vm, primary)

                is Step.RunScript -> RunAsRoot("Writing to the card", step.launcher, vm, primary)

                is Step.Writing -> {
                    if (step.phase == "writing") {
                        Title("Writing PB-OS to the card")
                        Progress(step.done, step.total)
                        Body("${formatBytes(step.done)} of ${formatBytes(step.total)}")
                    } else {
                        Busy(
                            when (step.phase) {
                                "checking" -> "Checking the download…"
                                "unmounting" -> "Getting the card ready…"
                                else -> "Starting…"
                            }
                        )
                    }
                    Body("Keep the card in. You can leave the app; writing continues in the background.")
                }

                is Step.CardCheck -> {
                    Title("Checking the card")
                    Progress(step.done, step.total)
                    Body("Reading the card back to make sure every byte is right · ${formatBytes(step.done)} of ${formatBytes(step.total)}")
                }

                is Step.Written -> {
                    Title("PB-OS is on your card!")
                    Body("PB-OS ${step.tag} was written to the card and checked.")
                    FirstStartNote(step.firstUpdate, step.updateFailed)
                    Body("Next, set up the boot menu that starts PB-OS from the card.")
                    Button(onClick = vm::openBootMenu, modifier = Modifier.focusRing().focusRequester(primary)) {
                        Text("Set Up Boot Menu")
                    }
                }

                Step.BootMenu -> {
                    Title("Set up the boot menu")
                    Body(
                        "To start PB-OS, your ${vm.handheld} needs a boot menu: OpenABL, " +
                            "by the Open Handheld Collective (the boot menu ROCKNIX uses too). " +
                            "Android stays: you choose it in the same menu."
                    )
                    Body("First, the app saves a copy of the original boot loader. Nothing is changed yet.")
                    Button(onClick = vm::backUpBootLoader, modifier = Modifier.focusRing().focusRequester(primary)) {
                        Text("Back Up Boot Loader")
                    }
                }

                is Step.AblRunScript -> RunAsRoot(
                    if (step.job == AblScript.BACKUP) "Backing up the boot loader" else "Installing the boot menu",
                    step.launcher, vm, primary,
                )

                is Step.AblWorking -> Busy(
                    if (step.job == AblScript.BACKUP) "Backing up the boot loader…" else "Installing the boot menu…"
                )

                is Step.AblBackedUp -> BackedUpStep(step, vm, primary)

                Step.AblInstallReady -> {
                    Title("Install the boot menu")
                    Warning(
                        "This replaces the boot loader of your ${vm.handheld} with the ${AblKnown.BUNDLED} boot menu. " +
                            "If anything goes wrong, the app puts the original back."
                    )
                    Button(onClick = vm::installBootMenu, modifier = Modifier.focusRing().focusRequester(primary)) {
                        Text("Install Boot Menu")
                    }
                }

                Step.BootMenuDone -> {
                    Title("The boot menu is installed!")
                    Body("To start PB-OS:")
                    Body(
                        "1.  Turn your ${vm.handheld} off.\n" +
                            "2.  Hold Volume Down while you turn it on. The OpenABL menu opens.\n" +
                            "3.  Volume Up and Down move, Power selects.\n" +
                            "4.  Set Boot mode to Linux and Device model to your handheld.\n" +
                            "5.  Set Boot source: SD card for PB-OS on the card, Auto for PB-OS on internal storage.\n" +
                            "6.  Choose Start (it reads Start Linux)."
                    )
                    AfterMenuSetUp()
                }

                Step.InternalChecking -> Busy("Checking internal storage…")

                is Step.InternalRunScript -> RunAsRoot(
                    if (step.job == InternalScript.PROBE) "Checking internal storage" else "Installing PB-OS",
                    step.launcher, vm, primary,
                )

                is Step.InternalFound -> InternalFoundStep(step.plan, vm, primary)

                is Step.InternalRefused -> {
                    Title("Can't install over internal storage")
                    Body(step.reason)
                    Button(onClick = vm::backToStart, modifier = Modifier.focusRing().focusRequester(primary)) { Text("Back") }
                }

                is Step.InternalWriting -> {
                    val label = when (step.phase) {
                        "writing" -> "Writing PB-OS to internal storage"
                        "verifying" -> "Checking what was written"
                        "home" -> "Copying home"
                        "system" -> "Copying the system"
                        else -> null
                    }
                    if (label != null) {
                        Title(label)
                        Progress(step.done, step.total)
                    } else {
                        Busy(
                            when (step.phase) {
                                "checking" -> "Checking the download and internal storage…"
                                "boot" -> "Setting up the boot partition…"
                                "finishing" -> "Finishing…"
                                "table" -> "Writing the new partition table…"
                                else -> "Starting…"
                            }
                        )
                    }
                    Body("Keep your ${vm.handheld} charged and on. You can leave the app; installing continues in the background.")
                }

                is Step.InternalDone -> {
                    Title("PB-OS is on internal storage!")
                    Body("PB-OS ${step.tag} was installed and checked. Android wasn't changed.")
                    FirstStartNote(step.firstUpdate, step.updateFailed)
                    Body("To start PB-OS:")
                    Body(
                        "1.  Turn your ${vm.handheld} off.\n" +
                            "2.  Hold Volume Down while you turn it on.\n" +
                            "3.  In the menu, set the boot source to Internal and the boot mode to Linux, then choose Start."
                    )
                    Body("The first start takes a little longer while PB-OS grows to fill its space. Android is still in the same menu.")
                }

                is Step.Failed -> {
                    Title("Something went wrong")
                    Body(step.message)
                    Button(onClick = vm::retry, modifier = Modifier.focusRing().focusRequester(primary)) { Text("Try again") }
                }
            }
        }
    }
}

/** Tools: Repair PB-OS (check, report, fixes). */
@Composable
private fun ToolsCard(state: RepairState, vm: InstallerViewModel, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf<RepairReport.Fix?>(null) }
    confirm?.let { fix ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(fix.label) },
            text = { Text(fix.explain) },
            confirmButton = { Button(onClick = { confirm = null; vm.applyFix(fix) }, modifier = Modifier.focusRing()) { Text("Do It") } },
            dismissButton = { TextButton(onClick = { confirm = null }, modifier = Modifier.focusRing()) { Text("Cancel") } },
        )
    }
    Card(modifier) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                RepairState.Menu -> {
                    Title("Tools")
                    Text("Repair PB-OS", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Body(
                        "For a PB-OS install on the microSD card or on internal storage that doesn't start any more, " +
                            "for example after an update, or a boot menu that disappeared. The check only reads; " +
                            "nothing is changed until you pick a fix."
                    )
                    Button(onClick = vm::checkPbOs, modifier = Modifier.focusRing()) { Text("Check PB-OS") }
                }
                RepairState.Checking -> Busy("Checking PB-OS…")
                is RepairState.RunScript -> RunAsRoot(
                    if (state.job == RepairScript.CHECK) "Checking PB-OS" else "Repairing PB-OS", state.launcher, vm, remember { FocusRequester() },
                )
                is RepairState.Fixing -> Busy("Repairing: ${state.fix.label}…")
                is RepairState.Report -> RepairReportView(state.report, vm) { confirm = it }
                is RepairState.Fixed -> {
                    Title("Repaired")
                    Body(state.message)
                    Button(onClick = vm::checkPbOs, modifier = Modifier.focusRing()) { Text("Check Again") }
                }
                is RepairState.Failed -> {
                    Title("Something went wrong")
                    Body(state.message)
                    Button(onClick = vm::checkPbOs, modifier = Modifier.focusRing()) { Text("Check Again") }
                }
            }
        }
    }
}

@Composable
private fun RepairReportView(report: RepairReport, vm: InstallerViewModel, onFix: (RepairReport.Fix) -> Unit) {
    Title("What the check found")
    if (report.installs.isEmpty()) {
        Body("No PB-OS install was found, on the microSD card or on internal storage.")
    }
    @Composable
    fun Findings(list: List<RepairReport.Finding>) {
        list.forEach { f ->
            val (mark, color) = when (f.level) {
                RepairReport.Level.Ok -> "✓" to MaterialTheme.colorScheme.primary
                RepairReport.Level.Note -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
                RepairReport.Level.Problem -> "✗" to MaterialTheme.colorScheme.error
            }
            Row {
                Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.widthIn(min = 24.dp))
                Text(f.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
    Text("Boot menu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Findings(report.bootMenu)
    report.installs.forEach { install ->
        Text(install.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Findings(install.findings)
    }
    if (report.fixes.isEmpty()) {
        Body(
            if (report.installs.isNotEmpty() && report.installs.all { it.healthy } && report.bootMenu.none { it.level == RepairReport.Level.Problem })
                "Nothing to repair was found."
            else "The app has no fix for this yet."
        )
    } else {
        Text("Fixes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        report.fixes.forEach { fix ->
            Body(fix.explain)
            Button(onClick = { onFix(fix) }, modifier = Modifier.focusRing()) { Text(fix.label) }
        }
    }
    OutlinedButton(onClick = vm::checkPbOs, modifier = Modifier.focusRing()) { Text("Check Again") }
}

/** A newer version of this app: Update App, its progress, and Android's permission step. */
@Composable
private fun AppUpdateBanner(vm: InstallerViewModel) {
    val state by vm.appUpdate.collectAsStateWithLifecycle()
    val s = state ?: return
    Card(Modifier.widthIn(max = 600.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (s) {
                is AppUpdateState.Available -> {
                    Text("PB-OS Installer ${s.release.version} is available (this is ${BuildConfig.VERSION_NAME}).")
                    Button(onClick = vm::updateApp, modifier = Modifier.focusRing()) { Text("Update App") }
                }
                is AppUpdateState.Downloading -> {
                    Text("Downloading PB-OS Installer ${s.release.version}…")
                    Progress(s.done, s.total)
                }
                is AppUpdateState.NeedsPermission -> {
                    Text("Android needs your OK once: allow PB-OS to install apps, then come back and tap Install.")
                    Button(onClick = vm::updateApp, modifier = Modifier.focusRing()) { Text("Allow / Install") }
                }
                is AppUpdateState.Installing -> {
                    Text("Confirm the update in Android's window. The app closes while it updates; open it again afterwards.")
                    OutlinedButton(onClick = vm::updateApp, modifier = Modifier.focusRing()) { Text("Show it Again") }
                }
                is AppUpdateState.Failed -> {
                    Text(s.message)
                    OutlinedButton(onClick = vm::updateApp, modifier = Modifier.focusRing()) { Text("Try Again") }
                }
            }
        }
    }
}

/** What the first start of PB-OS does with the update that came along. */
@Composable
private fun FirstStartNote(update: String?, failed: Boolean) {
    when {
        update == null -> {}
        failed -> Warning("Couldn't add update $update. Once PB-OS runs, install it from PB-OS Utils.")
        else -> Body(
            "On its first start, PB-OS installs update $update and restarts once. " +
                "That start takes a few minutes longer; don't turn it off."
        )
    }
}

/** A root job saved in Downloads, for the user to run as root ([what] needs root). */
@Composable
private fun RunAsRoot(what: String, launcher: String, vm: InstallerViewModel, primary: FocusRequester) {
    val context = LocalContext.current
    Title("One more step")
    Body("$what needs root access, which only your handheld's settings can give.")
    Body(
        if (vm.isRetroid) {
            "Open Handheld Settings → Advanced → Run Script as Root, and pick Download → $launcher. " +
                "Then come back here to follow the progress."
        } else {
            "Run Download → $launcher as root, then come back here to follow the progress."
        }
    )
    Button(
        onClick = { runCatching { context.startActivity(vm.handheldSettingsIntent()) } },
        modifier = Modifier.focusRing().focusRequester(primary),
    ) { Text(if (vm.isRetroid) "Open Handheld Settings" else "Open Settings") }
    Body("Waiting for the script to start…")
}

/** After the backup: save a copy elsewhere, then confirm with 5 taps (like unlocking developer options). */
@Composable
private fun BackedUpStep(step: Step.AblBackedUp, vm: InstallerViewModel, primary: FocusRequester) {
    var taps by rememberSaveable { mutableStateOf(0) }
    var saved by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val saveCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.saveBackupZip(uri) { saved = it }
    }
    Title("Boot loader backed up")
    Body("Slot A: ${slotLabel(step.slotA)}\nSlot B: ${slotLabel(step.slotB)}")
    step.documents?.let { Body("A copy is in $it on this handheld.") }
    Warning(
        "Save another copy somewhere else too (Google Drive, a USB drive, your computer). " +
            "You need the original to go back to stock or to install Android updates."
    )
    OutlinedButton(onClick = { saveCopy.launch(vm.backupZipName()) }, modifier = Modifier.focusRing()) {
        Text("Save a Copy…")
    }
    when (saved) {
        true -> Body("✓ Saved.")
        false -> Warning("Couldn't save the copy there. Try another place.")
        null -> {}
    }
    Button(
        onClick = { taps += 1; if (taps >= 5) vm.backupConfirmed() },
        modifier = Modifier.focusRing().focusRequester(primary),
    ) {
        Text(
            when (val left = 5 - taps) {
                5 -> "I Saved a Copy (tap 5 times)"
                1 -> "Tap 1 more time"
                else -> "Tap $left more times"
            }
        )
    }
}

private fun slotLabel(slot: String): String = when {
    slot.startsWith("stock: ") -> "original boot loader (${slot.removePrefix("stock: ")})"
    AblKnown.menuName(slot) != null -> AblKnown.menuLabel(AblKnown.menuName(slot)!!)
    else -> "unknown boot loader"
}

/**
 * After the boot menu is set up: how the next starts go and how to get to Android
 * (OpenABL's README: settings are kept; Volume Up boots Android while the boot mode is Linux).
 */
@Composable
private fun AfterMenuSetUp() {
    Body("The menu keeps these settings, so from then on your handheld starts PB-OS by itself.")
    Body(
        "Android is still there: hold Volume Up while you turn the handheld on to start it once, " +
            "or set Boot mode back to Android in the menu."
    )
    Body(
        "Don't choose Uninstall CFW in the menu: it erases PB-OS on internal storage " +
            "and all of Android's data."
    )
}

/** Downloaded: the last check before the card is erased and written. */
@Composable
private fun WriteStep(step: Step.Downloaded, card: SdCard?, vm: InstallerViewModel, primary: FocusRequester) {
    Title("PB-OS ${step.image.tag} is ready")
    Body("It's downloaded and checked. Next, write it to your microSD card or USB drive.")
    when {
        card == null -> Warning("Put the microSD card or USB drive back in to continue.")
        !card.bigEnough -> Warning("This card is too small. PB-OS needs a card or drive of 32 GB or bigger.")
        else -> Warning(
            "Writing ERASES EVERYTHING on the microSD card or USB drive" +
                (card.sizeBytes?.let { " (${formatBytes(it)})" } ?: "") + "."
        )
    }
    Button(
        onClick = vm::prepareWrite,
        enabled = card != null && card.bigEnough,
        modifier = Modifier.focusRing().focusRequester(primary),
    ) { Text("Write to Card / Drive") }
}

/** What the internal check found: what goes, what stays, how big PB-OS's parts will be. */
@Composable
private fun InternalFoundStep(plan: InternalLayout.Plan, vm: InstallerViewModel, primary: FocusRequester) {
    val kind = plan.kind.label
    // Over PB-OS, keeping HOME is the default: it's the choice that loses the least.
    val canKeep = plan.kind == InternalLayout.Kind.PbOs
    var keep by rememberSaveable { mutableStateOf(canKeep) }
    Title("$kind found on internal storage")
    Body(
        "Android: ${formatBytes(plan.androidBytes)}, stays exactly as it is.\n" +
            "$kind: ${formatBytes(plan.linuxBytes)}, becomes PB-OS."
    )
    Body(
        "PB-OS: boot ${formatBytes(plan.bootArea.length)}, system ${formatBytes(plan.storageArea.length)}, " +
            "games and files ${formatBytes(plan.homeArea.length)}."
    )
    if (canKeep) {
        Row(
            Modifier
                .focusRing(RoundedCornerShape(8.dp))
                .toggleable(value = keep, role = Role.Checkbox, onValueChange = { keep = it })
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = keep, onCheckedChange = null)
            Spacer(Modifier.size(12.dp))
            Text("Keep my games and files (HOME)")
        }
    }
    if (keep) {
        Body(
            "Kept: installed games, saves, your Steam login and settings, Decky and its plugins. " +
                "New: the system, so Wi-Fi networks, your password and system settings start fresh."
        )
    } else {
        Warning(
            "Installing ERASES the $kind install on internal storage, with its games, saves and settings. " +
                "Copy anything you want to keep first."
        )
    }
    Button(onClick = { vm.continueInternal(keep) }, modifier = Modifier.focusRing().focusRequester(primary)) { Text("Continue") }
    OutlinedButton(onClick = vm::backToStart, modifier = Modifier.focusRing()) { Text("Back") }
}

/** Downloaded, installing over internal storage: the last check, confirmed with 5 taps. */
@Composable
private fun InternalInstallStep(step: Step.Downloaded, vm: InstallerViewModel, primary: FocusRequester) {
    var taps by rememberSaveable { mutableStateOf(0) }
    val plan = vm.internalPlan
    val kind = plan?.kind?.label ?: "Linux"
    Title("PB-OS ${step.image.tag} is ready")
    Body("It's downloaded and checked. Next, install it over $kind on internal storage.")
    Warning(
        if (vm.keepHome) {
            "This replaces the PB-OS system. HOME (${plan?.let { formatBytes(it.homeArea.length) }}) with your games " +
                "and files stays. Android and its apps aren't touched."
        } else {
            "This ERASES the $kind install" + (plan?.let { " (${formatBytes(it.linuxBytes)})" } ?: "") +
                ", with its games, saves and settings. Android and its apps aren't touched."
        }
    )
    Button(
        onClick = { taps += 1; if (taps >= 5) { taps = 0; vm.prepareInternalInstall() } },
        enabled = plan != null,
        modifier = Modifier.focusRing().focusRequester(primary),
    ) {
        Text(
            when (val left = 5 - taps) {
                5 -> "Install PB-OS (tap 5 times)"
                1 -> "Tap 1 more time"
                else -> "Tap $left more times"
            }
        )
    }
}

/** Insert a card, then Continue: the app never moves on by itself when a card goes in. */
@Composable
private fun CardStep(card: SdCard?, vm: InstallerViewModel, primary: FocusRequester) {
    Title("Insert a microSD card or USB drive")
    Body("PB-OS runs from a microSD card or a USB drive of 32 GB or bigger. Put one in, then tap Continue.")
    when {
        card == null -> Body("No card found yet.")
        !card.bigEnough -> Warning(
            "This card is too small (${card.sizeBytes?.let(::formatBytes)}). " +
                "PB-OS needs a card or drive of 32 GB or bigger."
        )
        else -> Body("✓ Card found" + (card.sizeBytes?.let { ": ${formatBytes(it)}" } ?: "") + ".")
    }
    Button(
        onClick = vm::continueWithCard,
        enabled = card != null && card.bigEnough,
        modifier = Modifier.focusRing().focusRequester(primary),
    ) { Text("Continue") }
    HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    Body("Already have PB-OS or ArmadaOS on internal storage? PB-OS can go over it, in the same space, without a card.")
    OutlinedButton(onClick = vm::checkInternal, modifier = Modifier.focusRing()) { Text("Install Over Internal Linux") }
}

@Composable
private fun OfferStep(step: Step.Offer, card: SdCard?, vm: InstallerViewModel, primary: FocusRequester) {
    var understood by rememberSaveable { mutableStateOf(false) }
    val image = step.image
    // Release name only: "pb-os alpha v0.5.2: deep sleep on every device" -> "pb-os alpha v0.5.2".
    Title(image.title.substringBefore(':').trim())
    if (vm.tested == null) {
        Warning("UNTESTED DEVICE: this build allows installing on handhelds PB-OS was never tested on.")
    } else if (!vm.tested.hardwareTested) {
        Warning("NOT TESTED YET: nobody has run PB-OS on the ${vm.tested.name} yet.")
    }
    Body(
        "Device: ${vm.tested?.name ?: "${vm.info.manufacturer} ${vm.info.model}"}\n" +
            "Download: ${formatBytes(image.downloadSize)} (${image.parts.size} parts)" +
            when {
                step.needed == 0L -> ", already downloaded"
                step.needed < image.downloadSize -> ", ${formatBytes(step.needed)} still to fetch"
                else -> ""
            } +
            (step.update?.let { "\nUpdate: ${it.tag} (${formatBytes(it.downloadSize)}), installed on the first start" } ?: "")
    )
    if (step.updateUnknown) {
        Body("Couldn't check for a newer PB-OS update. PB-OS can update itself after it starts.")
    }
    val internal = vm.target == Target.Internal
    val kind = vm.internalPlan?.kind?.label ?: "Linux"
    Warning(
        if (internal && vm.keepHome) {
            "Installing PB-OS replaces the PB-OS system on internal storage. Your games and files in HOME stay; " +
                "Wi-Fi networks and system settings start fresh. Android isn't touched."
        } else if (internal) {
            "Installing PB-OS ERASES the $kind install on internal storage, with its games, saves and settings. " +
                "Android isn't touched."
        } else {
            "Installing PB-OS ERASES EVERYTHING on the microSD card or USB drive" +
                (listOfNotNull(card?.label, card?.sizeBytes?.let(::formatBytes))
                    .takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") ?: "") +
                ". Copy anything you want to keep off the card first."
        }
    )
    Row(
        Modifier
            .focusRing(RoundedCornerShape(8.dp))
            .focusRequester(primary)
            .toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it })
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = understood, onCheckedChange = null)
        Spacer(Modifier.size(12.dp))
        Text(
            when {
                internal && vm.keepHome -> "I understand the PB-OS system will be replaced"
                internal -> "I understand $kind will be erased"
                else -> "I understand the card will be erased"
            }
        )
    }
    Button(onClick = { vm.download(image) }, enabled = understood, modifier = Modifier.focusRing()) {
        Text("Download PB-OS")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(vm: InstallerViewModel, modifier: Modifier = Modifier) {
    val report by vm.report.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<Map<String, String>?>(null) }

    preview?.let { fields ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Send device report?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "This goes to the Project Barry Discord server, so we can add or fix support for " +
                            "this handheld. It is exactly the text below: model and firmware details that are " +
                            "the same on every unit, nothing about you."
                    )
                    Text(DeviceReport.text(fields), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(onClick = { vm.sendReport(fields); preview = null }, modifier = Modifier.focusRing()) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }, modifier = Modifier.focusRing()) { Text("Cancel") }
            },
        )
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                vm.tested?.name ?: "${vm.info.manufacturer} ${vm.info.model}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                listOfNotNull(
                    vm.info.soc?.label ?: "Unsupported chip",
                    when {
                        vm.tested == null -> "not tested with PB-OS"
                        vm.tested.hardwareTested -> "tested with PB-OS"
                        else -> "supported, not tested yet"
                    },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            val canSend = report == ReportState.Ready || report == ReportState.Done(ReportSender.Result.FAILED)
            if (vm.reportAvailable) Button(onClick = { preview = vm.reportFields() }, enabled = canSend, modifier = Modifier.focusRing()) {
                Text(
                    when (report) {
                        ReportState.Sending -> "Sending…"
                        ReportState.Done(ReportSender.Result.SENT), ReportState.Done(ReportSender.Result.DUPLICATE) -> "Report sent ✓"
                        else -> "Send Device Report"
                    }
                )
            }
            if (vm.reportAvailable) {
                val sent = report == ReportState.Done(ReportSender.Result.SENT) ||
                    report == ReportState.Done(ReportSender.Result.DUPLICATE)
                Text(
                    if (sent) {
                        "Thank you for sharing your device info with us, we will use it to make this app better."
                    } else {
                        "Send your device report to the Project Barry Discord server, so we can make this app better."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only problems get their own line; a sent report changes the text above.
                when ((report as? ReportState.Done)?.result) {
                    ReportSender.Result.LIMIT -> Text("Too many reports right now. Please try again later.", style = MaterialTheme.typography.bodyMedium)
                    ReportSender.Result.FAILED -> Text("Couldn't send the report. Check Wi-Fi and try again.", style = MaterialTheme.typography.bodyMedium)
                    else -> {}
                }
            }
        }
    }
}

/**
 * A clear outline on the focused control, for d-pad and controller users: drawn
 * just outside the control in the control's own [shape] (pill for buttons). The
 * app turns off Android's invisible minimum touch padding (MainActivity), so a
 * button's bounds are its visible edges and the outline follows them exactly.
 */
@Composable
internal fun Modifier.focusRing(shape: Shape = RoundedCornerShape(50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused) {
                val stroke = 3.dp.toPx()
                val inset = 3.dp.toPx() + stroke / 2
                val outline = shape.createOutline(
                    Size(size.width + 2 * inset, size.height + 2 * inset), layoutDirection, this,
                )
                translate(-inset, -inset) { drawOutline(outline, color, style = Stroke(stroke)) }
            }
        }
}

@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(24.dp))
        Spacer(Modifier.size(16.dp))
        Text(text)
    }
}

@Composable
private fun Progress(done: Long, total: Long) {
    LinearProgressIndicator(
        progress = { if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Warning(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
