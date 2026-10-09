package org.projectbarry.pbosinstaller.ui

/**
 * Just enough Markdown for docs/DEVICE-REPORT.md, which the app shows in a
 * pop-up: "#"/"##" headings, "- " bullets, "1." numbered items, paragraphs,
 * **bold**, *italic* (shown plain) and [links](url).
 */
object SimpleMarkdown {
    /** A run of text: bold or not, and a link when [url] is set. */
    data class Span(val text: String, val bold: Boolean = false, val url: String? = null)

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val spans: List<Span>) : Block
        /** A list item; [marker] is "•" or the item's number, e.g. "3.". */
        data class Bullet(val spans: List<Span>, val marker: String = "•") : Block
        /** Text shown exactly as is, in a fixed-width font (added by the app, not parsed). */
        data class Code(val text: String) : Block
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var bullet: StringBuilder? = null
        var marker = "•"

        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(inline(paragraph.toString()))
            paragraph.clear()
            bullet?.let { blocks += Block.Bullet(inline(it.toString()), marker) }
            bullet = null
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            val numbered = Regex("^(\\d+)\\.\\s+(.*)$").find(line)
            when {
                line.isBlank() -> flush()
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, plain(heading.groupValues[2]))
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flush()
                    marker = "•"
                    bullet = StringBuilder(line.drop(2).trim())
                }
                numbered != null -> {
                    flush()
                    marker = "${numbered.groupValues[1]}."
                    bullet = StringBuilder(numbered.groupValues[2].trim())
                }
                // A line indented under a bullet continues it; any other line continues the paragraph.
                bullet != null && raw.startsWith(" ") -> bullet!!.append(' ').append(line.trim())
                else -> {
                    bullet?.let { blocks += Block.Bullet(inline(it.toString()), marker); bullet = null }
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flush()
        return blocks
    }

    private val LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")

    /** Text with links and **bold** runs split out. */
    fun inline(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var bold = false
        fun plainText(part: String) {
            part.split("**").forEachIndexed { i, piece ->
                if (i > 0) bold = !bold
                val text = piece.replace(Regex("\\*([^*]+)\\*"), "$1")
                if (text.isNotEmpty()) spans += Span(text, bold)
            }
        }
        var at = 0
        for (m in LINK.findAll(text)) {
            plainText(text.substring(at, m.range.first))
            spans += Span(m.groupValues[1].replace("**", ""), bold, url = m.groupValues[2])
            at = m.range.last + 1
        }
        plainText(text.substring(at))
        return spans
    }

    /** All link addresses in the page, in order. */
    fun links(blocks: List<Block>): List<String> = blocks.flatMap {
        when (it) {
            is Block.Paragraph -> it.spans
            is Block.Bullet -> it.spans
            is Block.Heading, is Block.Code -> emptyList()
        }
    }.mapNotNull { it.url }

    /**
     * A plain-text document (a licence) as paragraphs: blank lines separate them,
     * the hard line wraps inside are joined so the text fits any screen width.
     */
    fun plainParagraphs(text: String): List<String> =
        text.split(Regex("\\n\\s*\\n"))
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotEmpty() }

    private fun plain(text: String) = LINK.replace(text) { it.groupValues[1] }.replace("**", "")
}
