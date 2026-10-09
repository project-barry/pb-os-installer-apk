package org.projectbarry.pbosinstaller.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.projectbarry.pbosinstaller.ui.SimpleMarkdown.Block
import org.projectbarry.pbosinstaller.ui.SimpleMarkdown.Span
import java.io.File

class SimpleMarkdownTest {
    @Test fun basics() {
        val blocks = SimpleMarkdown.parse(
            """
            # Title

            First line
            second line with **bold**.

            ## Section

            - one
            - two
              continued
            Ask on [Discord](https://discord.gg/x).
            """.trimIndent()
        )
        assertEquals(
            listOf(
                Block.Heading(1, "Title"),
                Block.Paragraph(listOf(Span("First line second line with "), Span("bold", true), Span("."))),
                Block.Heading(2, "Section"),
                Block.Bullet(listOf(Span("one"))),
                Block.Bullet(listOf(Span("two continued"))),
                Block.Paragraph(listOf(Span("Ask on "), Span("Discord", url = "https://discord.gg/x"), Span("."))),
            ),
            blocks,
        )
    }

    @Test fun boldAcrossLinkAndHeadingLinks() {
        assertEquals(
            listOf(Span("a "), Span("b", true), Span("c", true, url = "u"), Span(" d")),
            SimpleMarkdown.inline("a **b[c](u)** d"),
        )
        assertEquals(listOf(Block.Heading(2, "See Docs")), SimpleMarkdown.parse("## See [Docs](x)"))
    }

    /** The real page: every line of text shows up, and no Markdown marks are left. */
    @Test fun deviceReportPage() {
        val md = File("../docs/DEVICE-REPORT.md").readText()
        val blocks = SimpleMarkdown.parse(md)
        val text = blocks.joinToString("\n") {
            when (it) {
                is Block.Heading -> it.text
                is Block.Paragraph -> it.spans.joinToString("") { s -> s.text }
                is Block.Bullet -> it.spans.joinToString("") { s -> s.text }
            }
        }
        assertEquals(Block.Heading(1, "What's in a device report?"), blocks.first())
        assertTrue(blocks.count { it is Block.Heading && it.level == 2 } >= 3)
        assertTrue(blocks.count { it is Block.Bullet } >= 6)
        assertTrue(listOf("**", "](", "# ").none { it in text })
        assertEquals(listOf("https://discord.gg/euPurKCWc4"), SimpleMarkdown.links(blocks))
    }
}
