package org.projectbarry.pbosinstaller.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.projectbarry.pbosinstaller.ui.SimpleMarkdown.Block

/**
 * "What's in the Report?": docs/DEVICE-REPORT.md as bundled at build time, then
 * [report], the exact text "Send Device Report" would send from this handheld.
 */
@Composable
fun ReportInfoDialog(report: String, onClose: () -> Unit) = DocDialog(
    "DEVICE-REPORT.md",
    "What's in a device report?",
    extra = listOf(
        Block.Heading(2, "Your handheld's report"),
        Block.Paragraph(listOf(SimpleMarkdown.Span("This is exactly what Send Device Report would send from this handheld right now:"))),
        Block.Code(report),
    ),
    bodyEnd = { CopyReportButton(report) },
    onClose = onClose,
)

/** Copies the report shown above, for sending it by hand (e.g. on Discord). */
@Composable
private fun CopyReportButton(report: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = { clipboard.setText(AnnotatedString(report)); copied = true },
        modifier = Modifier.focusRing(),
    ) {
        Text(if (copied) "Copied ✓" else "Copy device info")
    }
}

/** "How Does it Work?": docs/ABOUT.md, with a QR code for the app's GitHub page at the bottom. */
@Composable
fun AboutDialog(onClose: () -> Unit) = DocDialog(
    "ABOUT.md",
    "How does it work?",
    bottomQr = Qr(REPO_URL, "Scan with your phone to open the app on GitHub", "QR code for PB-OS Installer on GitHub"),
    onClose = onClose,
)

private const val REPO_URL = "https://github.com/project-barry/pb-os-installer-apk"

/** A QR code for [url], with a caption under it. */
data class Qr(val url: String, val caption: String, val description: String)

/** "Licenses": docs/LICENSES.md, then the full licence texts, as bundled at build time. */
@Composable
fun LicensesDialog(onClose: () -> Unit) = DocDialog(
    "LICENSES.md",
    "Licenses",
    fullTexts = listOf(
        "GNU General Public License, version 3" to "licenses/GPL-3.0.txt",
        "GNU General Public License, version 2" to "licenses/GPL-2.0.txt",
        "Apache License 2.0" to "licenses/Apache-2.0.txt",
        "Bouncy Castle Licence" to "licenses/BouncyCastle.txt",
        "7-Zip licence" to "licenses/7-Zip.txt",
    ),
    onClose = onClose,
)

/**
 * A page from the app's assets in a pop-up, without a browser. The build copies
 * the pages from docs/ and LICENSES/ (app/build.gradle.kts), so the app always
 * shows them as they were when it was built. [fullTexts] are plain-text
 * documents (title to asset) added after the page, reflowed to the screen.
 * Closes with the Close button, the ✕, A, B/Back or a tap outside.
 */
@Composable
private fun DocDialog(
    asset: String,
    fallbackTitle: String,
    fullTexts: List<Pair<String, String>> = emptyList(),
    extra: List<Block> = emptyList(),
    bottomQr: Qr? = null,
    bodyEnd: (@Composable () -> Unit)? = null,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    fun read(path: String) = runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
    val blocks = remember(asset) {
        read(asset)?.let(SimpleMarkdown::parse)
            ?: listOf(Block.Paragraph(listOf(SimpleMarkdown.Span("This page is missing from this build of the app."))))
    }
    val appendix = remember(fullTexts) {
        fullTexts.flatMap { (heading, path) ->
            listOf(Block.Heading(2, heading)) +
                (read(path)?.let(SimpleMarkdown::plainParagraphs) ?: listOf("Missing from this build."))
                    .map { Block.Paragraph(listOf(SimpleMarkdown.Span(it))) }
        }
    }
    val title = (blocks.firstOrNull() as? Block.Heading)?.takeIf { it.level == 1 }?.text ?: fallbackTitle
    val body = (if ((blocks.firstOrNull() as? Block.Heading)?.level == 1) blocks.drop(1) else blocks) + extra + appendix
    PagePopup(title, body, bottomQr, onClose, bodyEnd)
}

/**
 * GitHub or Discord: one line of text, the link (tappable) and a QR code for it.
 * A Discord invite gets its QR code from the paragraph itself; other links get
 * it at the bottom.
 */
@Composable
private fun LinkDialog(title: String, text: String, url: String, onClose: () -> Unit) {
    val body = listOf(
        Block.Paragraph(listOf(SimpleMarkdown.Span(text))),
        Block.Paragraph(listOf(SimpleMarkdown.Span(url.removePrefix("https://"), url = url))),
    )
    val qr = if (isDiscordInvite(url)) null else Qr(url, "Scan with your phone to open it", "QR code for $title")
    PagePopup(title, body, qr, onClose)
}

@Composable
fun GitHubDialog(onClose: () -> Unit) = LinkDialog(
    "GitHub",
    "PB-OS Installer's code, releases and documents are on GitHub.",
    REPO_URL,
    onClose,
)

@Composable
fun DiscordDialog(onClose: () -> Unit) = LinkDialog(
    "Discord",
    "Join the Project Barry community on Discord for help, news and testing.",
    DISCORD_URL,
    onClose,
)

private const val DISCORD_URL = "https://discord.gg/euPurKCWc4"

private fun isDiscordInvite(url: String) = "discord.gg/" in url || "discord.com/invite/" in url

/** The pop-up frame: title with ✕, scrolling body, optional QR at the bottom, Close. */
@Composable
private fun PagePopup(
    title: String,
    body: List<Block>,
    bottomQr: Qr?,
    onClose: () -> Unit,
    bodyEnd: (@Composable () -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val rootFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    val uriHandler = LocalUriHandler.current
    // Opens Discord or the browser; a handheld without either just does nothing.
    val openLink: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 640.dp)
                .heightIn(max = maxHeight)
                // Controller: A or B close (every button here closes, so A needs no
                // selected button, which a tap-opened pop-up doesn't have); d-pad
                // up/down scroll the text; left/right move between ✕ and Close.
                .onPreviewKeyEvent { e ->
                    if (e.key in CLOSE_KEYS) {
                        if (e.type == KeyEventType.KeyUp) onClose()
                        return@onPreviewKeyEvent true
                    }
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (e.key) {
                        Key.DirectionDown -> 160f
                        Key.DirectionUp -> -160f
                        else -> return@onPreviewKeyEvent false
                    }
                    val canMove = if (step > 0) scroll.canScrollForward else scroll.canScrollBackward
                    if (canMove) scope.launch { scroll.animateScrollBy(step) }
                    canMove
                }
                // The pop-up itself takes focus when it opens: a pop-up opened by a tap has
                // nothing selected, and Android would spend the first controller press on
                // selecting something instead of delivering it.
                .onFocusChanged { hasFocus = it.hasFocus }
                .focusRequester(rootFocus)
                .focusable(),
        ) {
            Column(Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).padding(top = 8.dp),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.focusRing(CircleShape)) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scroll)
                        .padding(top = 8.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    body.forEach { block -> MarkdownBlock(block, openLink) }
                    bottomQr?.let { QrBlock(it) }
                    bodyEnd?.invoke()
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onClose, modifier = Modifier.focusRing()) {
                        Text("Close")
                    }
                }
            }
        }
    }
    // The dialog window attaches a moment after composition: retry until focus lands.
    LaunchedEffect(Unit) {
        repeat(40) {
            if (hasFocus) return@LaunchedEffect
            runCatching { rootFocus.requestFocus() }
            delay(50)
        }
    }
}

private val CLOSE_KEYS = setOf(Key.ButtonA, Key.ButtonB, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

@Composable
private fun MarkdownBlock(block: Block, openLink: (String) -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    when (block) {
        is Block.Heading -> Text(
            block.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp),
        )
        is Block.Paragraph -> {
            Text(annotated(block.spans, linkColor, openLink), style = MaterialTheme.typography.bodyLarge)
            block.spans.mapNotNull { it.url }.filter(::isDiscordInvite)
                .distinct().forEach {
                    QrBlock(Qr(it, "Scan with your phone to join our Discord", "QR code for the Project Barry Discord"))
                }
        }
        is Block.Code -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                block.text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp),
            )
        }
        is Block.Bullet -> Row {
            Text("${block.marker}  ", style = MaterialTheme.typography.bodyLarge)
            Text(annotated(block.spans, linkColor, openLink), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A scannable code, for opening a link on a phone. */
@Composable
private fun QrBlock(qr: Qr) {
    val bitmap = remember(qr.url) {
        val modules = QrCode.modules(qr.url)
        val size = modules.size
        val pixels = IntArray(size * size) { i ->
            if (modules[i / size][i % size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
        }
        android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Column(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Dark on white with a quiet zone, the way phone cameras read best; sharp module edges.
        Image(
            bitmap = bitmap,
            contentDescription = qr.description,
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(
            qr.caption,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun annotated(spans: List<SimpleMarkdown.Span>, linkColor: Color, openLink: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            val style = SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                color = if (span.url != null) linkColor else Color.Unspecified,
                textDecoration = if (span.url != null) TextDecoration.Underline else null,
            )
            if (span.url != null) {
                withLink(LinkAnnotation.Url(span.url, TextLinkStyles(style)) { openLink(span.url) }) { append(span.text) }
            } else {
                withStyle(style) { append(span.text) }
            }
        }
    }
