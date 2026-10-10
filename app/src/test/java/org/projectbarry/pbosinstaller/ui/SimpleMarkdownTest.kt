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

    @Test fun licenceTextsReflow() {
        for (name in listOf("GPL-2.0", "GPL-3.0", "Apache-2.0", "BouncyCastle", "7-Zip")) {
            val paragraphs = SimpleMarkdown.plainParagraphs(File("../LICENSES/$name.txt").readText())
            assertTrue(name, paragraphs.size >= 4)
            assertTrue(name, paragraphs.none { "\n" in it || "  " in it })
        }
        assertEquals(listOf("a b", "c"), SimpleMarkdown.plainParagraphs("  a\n   b\n\n\n c\n"))
    }

    @Test fun licensesPageLinks() {
        val blocks = SimpleMarkdown.parse(File("../docs/LICENSES.md").readText())
        assertEquals(Block.Heading(1, "Licenses"), blocks.first())
        val links = SimpleMarkdown.links(blocks)
        assertEquals("https://github.com/project-barry/pb-os-installer-apk", links.first())
        assertTrue("https://www.7-zip.org/download.html" in links)
    }

    @Test fun numberedItemsAndItalic() {
        assertEquals(
            listOf(
                Block.Bullet(listOf(Span("First thing")), "1."),
                Block.Bullet(listOf(Span("Second, wrapped (Coming soon)")), "2."),
                Block.Paragraph(listOf(Span("After."))),
            ),
            SimpleMarkdown.parse("1. First thing\n2. Second,\n   wrapped *(Coming soon)*\n\nAfter."),
        )
    }

    @Test fun aboutPage() {
        val blocks = SimpleMarkdown.parse(File("../docs/ABOUT.md").readText())
        assertEquals(Block.Heading(1, "How does it work?"), blocks.first())
        assertEquals((1..7).map { "$it." }, blocks.filterIsInstance<Block.Bullet>().map { it.marker })
        assertEquals(listOf("https://github.com/project-barry/pb-os-installer-apk"), SimpleMarkdown.links(blocks))
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
                is Block.Code -> it.text
            }
        }
        assertEquals(Block.Heading(1, "What's in a device report?"), blocks.first())
        assertTrue(blocks.count { it is Block.Heading && it.level == 2 } >= 3)
        assertTrue(blocks.count { it is Block.Bullet } >= 6)
        assertTrue(listOf("**", "](", "# ").none { it in text })
        assertEquals(listOf("https://discord.gg/euPurKCWc4"), SimpleMarkdown.links(blocks))
    }
}
