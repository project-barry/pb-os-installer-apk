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
import org.projectbarry.pbosinstaller.abl.AblScript
import org.projectbarry.pbosinstaller.device.Devices
import org.projectbarry.pbosinstaller.report.DeviceReport
import org.projectbarry.pbosinstaller.report.ReportSender
import org.projectbarry.pbosinstaller.storage.SdCard

@Composable
fun InstallerScreen(vm: InstallerViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()
    // Screen stays on while a root job runs, so the progress stays in view (the
    // job itself also keeps the handheld awake, see ScriptKit).
    val busy = step is Step.Writing || step is Step.CardCheck || step is Step.AblWorking ||
        step is Step.RunScript || step is Step.AblRunScript
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
                val showDevice = step is Step.Card || step is Step.Untested || step is Step.WrongChip
                Header()
                if (wide && showDevice) {
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
                    if (vm.tested != null) Body("PB-OS has been tested on the ${vm.fullName}.")
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

                is Step.Downloaded -> WriteStep(step, card, vm, primary)

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
                    Body("Next, set up the boot menu that starts PB-OS from the card.")
                    Button(onClick = vm::openBootMenu, modifier = Modifier.focusRing().focusRequester(primary)) {
                        Text("Set Up Boot Menu")
                    }
                }

                Step.BootMenu -> {
                    Title("Set up the boot menu")
                    Body(
                        "To start PB-OS from the card, your ${vm.handheld} needs the ROCKNIX boot menu. " +
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
                        "This replaces the boot loader of your ${vm.handheld} with the ROCKNIX boot menu. " +
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
                            "2.  Hold Volume Down while you turn it on.\n" +
                            "3.  In the menu, set the device model, set the boot mode to Linux, then choose Start."
                    )
                    Body("Android is still there: choose it in the same menu.")
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
    slot.startsWith("rocknix: ") -> slot.removePrefix("rocknix: ") + " boot menu"
    else -> "unknown boot loader"
}

/** Downloaded: the last check before the card is erased and written. */
@Composable
private fun WriteStep(step: Step.Downloaded, card: SdCard?, vm: InstallerViewModel, primary: FocusRequester) {
    Title("PB-OS ${step.image.tag} is ready")
    Body("It's downloaded and checked. Next, write it to your microSD card.")
    when {
        card == null -> Warning("Put the microSD card back in to continue.")
        !card.bigEnough -> Warning("This card is too small. PB-OS needs a microSD card of 32 GB or bigger.")
        else -> Warning(
            "Writing ERASES EVERYTHING on the microSD card" +
                (card.sizeBytes?.let { " (${formatBytes(it)})" } ?: "") + "."
        )
    }
    Button(
        onClick = vm::prepareWrite,
        enabled = card != null && card.bigEnough,
        modifier = Modifier.focusRing().focusRequester(primary),
    ) { Text("Write to SD Card") }
}

/** Insert a card, then Continue: the app never moves on by itself when a card goes in. */
@Composable
private fun CardStep(card: SdCard?, vm: InstallerViewModel, primary: FocusRequester) {
    Title("Insert a microSD card")
    Body("PB-OS runs from a microSD card of 32 GB or bigger. Put one in the card slot, then tap Continue.")
    when {
        card == null -> Body("No card found yet.")
        !card.bigEnough -> Warning(
            "This card is too small (${card.sizeBytes?.let(::formatBytes)}). " +
                "PB-OS needs a microSD card of 32 GB or bigger."
        )
        else -> Body("✓ Card found" + (card.sizeBytes?.let { ": ${formatBytes(it)}" } ?: "") + ".")
    }
    Button(
        onClick = vm::continueWithCard,
        enabled = card != null && card.bigEnough,
        modifier = Modifier.focusRing().focusRequester(primary),
    ) { Text("Continue") }
}

@Composable
private fun OfferStep(step: Step.Offer, card: SdCard?, vm: InstallerViewModel, primary: FocusRequester) {
    var understood by rememberSaveable { mutableStateOf(false) }
    val image = step.image
    // Release name only: "pb-os alpha v0.5.2: deep sleep on every device" -> "pb-os alpha v0.5.2".
    Title(image.title.substringBefore(':').trim())
    if (vm.tested == null) {
        Warning("UNTESTED DEVICE: this build allows installing on handhelds PB-OS was never tested on.")
    }
    Body(
        "Device: ${vm.tested?.name ?: "${vm.info.manufacturer} ${vm.info.model}"}\n" +
            "Download: ${formatBytes(image.downloadSize)} (${image.parts.size} parts)" +
            when {
                step.needed == 0L -> ", already downloaded"
                step.needed < image.downloadSize -> ", ${formatBytes(step.needed)} still to fetch"
                else -> ""
            }
    )
    Warning(
        "Installing PB-OS ERASES EVERYTHING on the microSD card" +
            (listOfNotNull(card?.label, card?.sizeBytes?.let(::formatBytes))
                .takeIf { it.isNotEmpty() }?.joinToString(", ", " (", ")") ?: "") +
            ". Copy anything you want to keep off the card first."
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
        Text("I understand the card will be erased")
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
                    if (vm.tested != null) "tested with PB-OS" else "not tested with PB-OS",
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
