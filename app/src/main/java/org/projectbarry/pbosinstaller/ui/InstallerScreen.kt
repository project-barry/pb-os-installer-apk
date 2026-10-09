package org.projectbarry.pbosinstaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
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
import org.projectbarry.pbosinstaller.device.Devices
import org.projectbarry.pbosinstaller.storage.SdCard

@Composable
fun InstallerScreen(vm: InstallerViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()
    val card by vm.card.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("PB-OS Installer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            StepCard(step, card, vm)
            DeviceCard(vm)
        }
    }
}

@Composable
private fun StepCard(step: Step, card: SdCard?, vm: InstallerViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (step) {
                Step.Checking, Step.LoadingRelease -> Busy(
                    if (step == Step.Checking) "Checking this device…" else "Looking for the newest PB-OS release…"
                )

                Step.WrongChip -> {
                    Title("This device can't run PB-OS")
                    Body(
                        "PB-OS only runs on handhelds with a Snapdragon 8 Gen 2 (SM8550) or " +
                            "Snapdragon 8 Gen 3 (SM8650) chip. This one reports \"${vm.info.socModel.ifBlank { "unknown" }}\"."
                    )
                }

                Step.NeedCard -> {
                    Title("Insert a microSD card")
                    Body(
                        "PB-OS runs from a microSD card. Put one in the card slot (32 GB or bigger). " +
                            "The app carries on by itself once it sees the card."
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(12.dp))
                        Text("Waiting for a card…")
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
                        "Got one of those and still see this? Tap \"Copy device info\" below and send it to us " +
                            "on Discord, so we can add your device."
                    )
                }

                is Step.Offer -> OfferStep(step, card, vm)

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
                    Button(onClick = vm::retry) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun OfferStep(step: Step.Offer, card: SdCard?, vm: InstallerViewModel) {
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
            (card?.let { c -> " (${c.label}${c.sizeBytes?.let { ", ${formatBytes(it)}" } ?: ""})" } ?: "") +
            ". Copy anything you want to keep off the card first."
    )
    if (card != null && !card.bigEnough) {
        Warning("This card is too small. PB-OS needs a microSD card of 32 GB or bigger.")
        return
    }
    Row(
        Modifier.toggleable(value = understood, role = Role.Checkbox, onValueChange = { understood = it }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = understood, onCheckedChange = null)
        Text("I understand the card will be erased")
    }
    Button(onClick = { vm.download(image) }, enabled = understood) {
        Text("Download PB-OS")
    }
}

@Composable
private fun DeviceCard(vm: InstallerViewModel) {
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(vm.info.report())) }) {
                    Text("Copy device info")
                }
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else "Details") }
            }
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
