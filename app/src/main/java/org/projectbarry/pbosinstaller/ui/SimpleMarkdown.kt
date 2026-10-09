package org.projectbarry.pbosinstaller.ui

/**
 * Just enough Markdown for docs/DEVICE-REPORT.md, which the app shows in a
 * pop-up: "#"/"##" headings, "- " bullets, paragraphs, **bold** and [links](url).
 */
object SimpleMarkdown {
    /** A run of text: bold or not, and a link when [url] is set. */
    data class Span(val text: String, val bold: Boolean = false, val url: String? = null)

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val spans: List<Span>) : Block
        data class Bullet(val spans: List<Span>) : Block
    }

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var bullet: StringBuilder? = null

        fun flush() {
            if (paragraph.isNotBlank()) blocks += Block.Paragraph(inline(paragraph.toString()))
            paragraph.clear()
            bullet?.let { blocks += Block.Bullet(inline(it.toString())) }
            bullet = null
        }

        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val heading = Regex("^(#{1,6})\\s+(.*)$").find(line)
            when {
                line.isBlank() -> flush()
                heading != null -> {
                    flush()
                    blocks += Block.Heading(heading.groupValues[1].length, plain(heading.groupValues[2]))
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    flush()
                    bullet = StringBuilder(line.drop(2).trim())
                }
                // A line indented under a bullet continues it; any other line continues the paragraph.
                bullet != null && raw.startsWith(" ") -> bullet!!.append(' ').append(line.trim())
                else -> {
                    bullet?.let { blocks += Block.Bullet(inline(it.toString())); bullet = null }
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
                if (piece.isNotEmpty()) spans += Span(piece, bold)
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
            is Block.Heading -> emptyList()
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
