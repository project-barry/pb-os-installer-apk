package org.projectbarry.pbosinstaller.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.projectbarry.pbosinstaller.BuildConfig
import org.projectbarry.pbosinstaller.device.Devices
import org.projectbarry.pbosinstaller.report.DeviceReport
import org.projectbarry.pbosinstaller.report.ReportSender
import org.projectbarry.pbosinstaller.storage.SdCard

@Composable
fun InstallerScreen(vm: InstallerViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()

    // Laid out from the window's current size, so it fits any screen shape,
    // rotation or split-screen: side by side when wide, one column otherwise.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val gap = if (maxWidth < 400.dp || maxHeight < 400.dp) 12.dp else 20.dp
        val wide = maxWidth >= 560.dp && maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize().padding(gap), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(
                    Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Header()
                    StepCard(step, card, vm)
                    Footer()
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    DeviceCard(vm)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                Header()
                StepCard(step, card, vm)
                DeviceCard(vm)
                Footer()
            }
        }
    }
}

/** App version and the Licenses pop-up. */
@Composable
private fun Footer() {
    var show by remember { mutableStateOf(false) }
    if (show) LicensesDialog(onClose = { show = false })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "PB-OS Installer ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(8.dp))
        TextButton(onClick = { show = true }, modifier = Modifier.focusRing()) { Text("Licenses") }
    }
}

@Composable
private fun Header() =
    Text("PB-OS Installer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

@Composable
private fun StepCard(step: Step, card: SdCard?, vm: InstallerViewModel) {
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
    Card(Modifier.fillMaxWidth()) {
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
                    Title(if (vm.tested != null) "Your handheld is supported" else "Untested handheld")
                    if (vm.tested == null) {
                        Warning("UNTESTED DEVICE: this build allows installing on handhelds PB-OS was never tested on.")
                    }
                    Body(
                        "${vm.tested?.name ?: "${vm.info.manufacturer} ${vm.info.model}"} · ${vm.info.soc?.label}" +
                            (if (vm.tested != null) "\nPB-OS has been tested on this handheld." else "")
                    )
                    Body("Next, the app looks up the newest PB-OS release on GitHub. Nothing is downloaded yet.")
                    Button(onClick = vm::lookUpRelease, modifier = Modifier.focusRing().focusRequester(primary)) {
                        Text("Look up newest release")
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
                            (if (vm.reportAvailable) "\"Send Device Report\"" else "\"Copy device info\" and send it to us on Discord") +
                            " below, so we can add your device."
                    )
                }

                is Step.Offer -> OfferStep(step, card, vm, primary)

                is Step.Downloading -> {
                    Title("Downloading PB-OS")
                    val p = step.progress
                    Progress(p.done, p.total)
                    Body("Part ${p.part} of ${p.parts} · ${formatBytes(p.done)} of ${formatBytes(p.total)}")
                    Body("You can leave the app. The download keeps going in the background.")
                }

                is Step.Verifying -> {
                    Title("Checking the download")
                    Progress(step.done, step.total)
                }

                is Step.Downloaded -> {
                    Title("PB-OS ${step.image.tag} is downloaded and checked")
                    Body("Writing it to the microSD card comes in the next version of this app.")
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
    Title(image.title)
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
        Text("I understand the card will be erased")
    }
    Button(onClick = { vm.download(image) }, enabled = understood, modifier = Modifier.focusRing()) {
        Text("Download PB-OS")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceCard(vm: InstallerViewModel) {
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    if (showInfo) ReportInfoDialog(onClose = { showInfo = false })
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
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
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
            if (open) {
                Text(vm.info.report(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
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
            if (vm.reportAvailable) Text(
                "Send your device report to the Project Barry Discord server, so we can make this app better",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            (report as? ReportState.Done)?.takeIf { vm.reportAvailable }?.let { done ->
                Text(
                    when (done.result) {
                        ReportSender.Result.SENT -> "Sent. Thanks!"
                        ReportSender.Result.DUPLICATE -> "We already have this report. Thanks!"
                        ReportSender.Result.LIMIT -> "Too many reports right now. Please try again later."
                        ReportSender.Result.FAILED -> "Couldn't send the report. Check Wi-Fi and try again."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            // Wraps onto a second line on narrow panes (the Nova's right half).
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showInfo = true }, modifier = Modifier.focusRing()) {
                    Text("What's in the report?")
                }
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(vm.info.report())) },
                    modifier = Modifier.focusRing(),
                ) {
                    Text("Copy device info")
                }
                TextButton(onClick = { open = !open }, modifier = Modifier.focusRing()) { Text(if (open) "Hide details" else "Details") }
            }
        }
    }
}

/** A clear outline on the focused control, for d-pad and controller users. */
@Composable
internal fun Modifier.focusRing(shape: Shape = RoundedCornerShape(50)): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(3.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
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
