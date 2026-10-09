package org.projectbarry.pbosinstaller.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.projectbarry.pbosinstaller.ui.SimpleMarkdown.Block

/**
 * "What's in the report?": docs/DEVICE-REPORT.md as it was when this APK was
 * built (copied into the app's assets by the build), shown without a browser.
 * Closes with the Close button, the ✕, Back (controller B) or a tap outside.
 */
@Composable
fun ReportInfoDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember {
        runCatching { context.assets.open("DEVICE-REPORT.md").bufferedReader().use { it.readText() } }
            .map(SimpleMarkdown::parse)
            .getOrDefault(listOf(Block.Paragraph(listOf(SimpleMarkdown.Span("This page is missing from this build of the app.")))))
    }
    val title = (blocks.firstOrNull() as? Block.Heading)?.takeIf { it.level == 1 }?.text ?: "What's in a device report?"
    val body = if ((blocks.firstOrNull() as? Block.Heading)?.level == 1) blocks.drop(1) else blocks

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
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
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
            block.spans.mapNotNull { it.url }.filter { "discord.gg/" in it || "discord.com/invite/" in it }
                .distinct().forEach { DiscordQr(it) }
        }
        is Block.Bullet -> Row {
            Text("•  ", style = MaterialTheme.typography.bodyLarge)
            Text(annotated(block.spans, linkColor, openLink), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A scannable code for a Discord invite, for joining from a phone. */
@Composable
private fun DiscordQr(url: String) {
    val bitmap = remember(url) {
        val modules = QrCode.modules(url)
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
            contentDescription = "QR code for the Project Barry Discord",
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(168.dp).clip(RoundedCornerShape(8.dp)),
        )
        Text(
            "Scan with your phone to join our Discord",
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
