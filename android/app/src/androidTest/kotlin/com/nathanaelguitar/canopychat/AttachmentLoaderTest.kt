package com.nathanaelguitar.canopychat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nathanaelguitar.canopychat.core.ChatAttachmentLoader
import com.nathanaelguitar.canopychat.core.MarkdownBlock
import com.nathanaelguitar.canopychat.core.MarkdownBlockParser
import com.nathanaelguitar.canopychat.core.MarkdownSourceParser
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a real device. Covers the pieces of the Swift→Kotlin port whose behavior
 * cannot be confirmed by the compiler: PDF text extraction and the markdown parsers.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentLoaderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PDFBoxResourceLoader.init(context)
    }

    @Test
    fun extractsTextFromPdf() {
        val pdf = File(context.cacheDir, "canopy_test.pdf")
        // The fixture is packaged in the test APK, so it comes from the instrumentation
        // context — the app-under-test context has its own, different asset manager.
        InstrumentationRegistry.getInstrumentation().context.assets.open("canopy_test.pdf").use { input ->
            pdf.outputStream().use { output -> input.copyTo(output) }
        }

        val attachment = ChatAttachmentLoader.attachment(context, android.net.Uri.fromFile(pdf))
        requireNotNull(attachment) { "PDF attachment failed to load" }

        val text = attachment.extractedText.orEmpty()
        assertTrue("extracted text was empty", text.isNotBlank())
        assertTrue("missing title line, got: $text", text.contains("CanopyChat PDF extraction test"))
        assertTrue("missing body line, got: $text", text.contains("quick brown fox"))
        assertTrue("missing numeric line, got: $text", text.contains("42"))
        assertTrue("should not be a placeholder, got: $text", !text.startsWith("[PDF attached"))

        // Raw bytes are dropped for non-image attachments; only the text is kept.
        assertEquals(0, attachment.data.size)
    }

    @Test
    fun corruptPdfProducesPlaceholderNotCrash() {
        val bogus = File(context.cacheDir, "bogus.pdf")
        bogus.writeBytes("%PDF-1.4 this is not a real pdf".toByteArray())

        val attachment = ChatAttachmentLoader.attachment(context, android.net.Uri.fromFile(bogus))
        requireNotNull(attachment) { "bogus PDF returned null instead of a placeholder" }
        assertTrue(
            "expected a placeholder, got: ${attachment.extractedText}",
            attachment.extractedText.orEmpty().startsWith("[PDF attached")
        )
    }

    @Test
    fun parsesMarkdownBlocks() {
        val blocks = MarkdownBlockParser.parse(
            """
            ## Heading two
            Body paragraph one
            continues here.

            - first bullet
            * second bullet
            1. numbered item

            ```
            code line
            ```

            | a | b |
            | - | - |
            """.trimIndent()
        )

        assertTrue(blocks.any { it is MarkdownBlock.Heading && it.level == 2 && it.text == "Heading two" })
        // Consecutive non-empty lines fold into a single paragraph, matching iOS.
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph && it.text == "Body paragraph one continues here." })
        assertEquals(2, blocks.count { it is MarkdownBlock.Bullet })
        assertTrue(blocks.any { it is MarkdownBlock.Numbered && it.number == 1 && it.text == "numbered item" })
        assertTrue(blocks.any { it is MarkdownBlock.Code && it.text == "code line" })
        assertTrue(blocks.any { it is MarkdownBlock.Table && it.rows.size == 2 })
    }

    @Test
    fun splitsTrailingSources() {
        val (body, sources) = MarkdownSourceParser.splitTrailingSources(
            """
            Four days is the outer limit.

            Sources
            - [FoodSafety.gov — Cold Food Storage Chart](https://www.foodsafety.gov/food-safety-charts/cold-food-storage-charts)
            """.trimIndent()
        )

        assertEquals("Four days is the outer limit.", body)
        assertEquals(1, sources.size)
        assertEquals("foodsafety.gov", sources.first().host)
        assertTrue(sources.first().url.startsWith("https://"))
    }

    @Test
    fun leavesBodyAloneWhenThereAreNoSources() {
        val content = "No citations here.\n\nJust prose."
        val (body, sources) = MarkdownSourceParser.splitTrailingSources(content)
        assertEquals(content, body)
        assertTrue(sources.isEmpty())
    }
}
