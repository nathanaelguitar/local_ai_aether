package com.nathanaelguitar.canopychat.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Port of AetherImageNormalizer in iphone/AetherChat/ChatView.swift. */
object ImageNormalizer {
    const val MAX_DIMENSION = 768
    private const val COMPRESSION_QUALITY = 76 // iOS uses 0.76

    fun jpegData(data: ByteArray): ByteArray {
        val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull()
            ?: return data
        return jpegData(bitmap) ?: data
    }

    fun jpegData(bitmap: Bitmap): ByteArray? {
        val (width, height) = resizedSize(bitmap.width, bitmap.height)
        // iOS draws onto an opaque white canvas so transparency does not turn black.
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(output).apply {
            drawColor(Color.WHITE)
            drawBitmap(
                Bitmap.createScaledBitmap(bitmap, width, height, true),
                0f,
                0f,
                null
            )
        }
        return ByteArrayOutputStream().use { stream ->
            if (!output.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, stream)) return null
            stream.toByteArray()
        }
    }

    /** Convenience for the camera capture path, which hands back a Bitmap directly. */
    fun attachmentFromBitmap(bitmap: Bitmap): ChatAttachment? {
        val data = jpegData(bitmap) ?: return null
        return ChatAttachment(
            data = data,
            mimeType = "image/jpeg",
            filename = "camera-${System.currentTimeMillis()}.jpg"
        )
    }

    private fun resizedSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= MAX_DIMENSION || longest <= 0) {
            return max(width, 1) to max(height, 1)
        }
        val scale = MAX_DIMENSION.toDouble() / longest
        return max((width * scale).toInt(), 1) to max((height * scale).toInt(), 1)
    }
}

/** Port of ChatAttachmentLoader in iphone/AetherChat/ChatView.swift. */
object ChatAttachmentLoader {
    const val MAX_EXTRACTED_CHARACTERS = 80_000

    /**
     * Files above this size are not read into memory at all; a placeholder attachment
     * keeps the UX intact while protecting against memory spikes on import.
     */
    const val MAX_IMPORT_BYTES = 32 * 1024 * 1024

    private val textLikeExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "css",
        "js", "ts", "tsx", "jsx", "swift", "kt", "kts", "java", "py", "rb", "go",
        "rs", "c", "h", "cpp", "hpp", "m", "mm", "sql", "yaml", "yml", "toml", "ini",
        "log", "sh", "zsh", "bash"
    )

    fun attachment(context: Context, uri: Uri): ChatAttachment? = runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "application/octet-stream"
        val (filename, size) = queryMetadata(context, uri)

        if (size != null && size > MAX_IMPORT_BYTES) {
            return@runCatching ChatAttachment(
                data = ByteArray(0),
                mimeType = mimeType,
                filename = filename,
                extractedText = "[File too large to attach (${size / 1_048_576} MB). " +
                    "CanopyChat can read files up to ${MAX_IMPORT_BYTES / 1_048_576} MB.]"
            )
        }

        val data = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null

        if (mimeType.startsWith("image/")) {
            return@runCatching ChatAttachment(
                data = ImageNormalizer.jpegData(data),
                mimeType = "image/jpeg",
                filename = filename
            )
        }

        // Only the extracted text is ever used for non-image files (prompt building,
        // search indexing, sharing). Dropping the raw bytes keeps a PDF-heavy chat from
        // ballooning the SQLite store and launch-time memory.
        val text = extractedText(context, data, filename, mimeType)
        ChatAttachment(
            data = ByteArray(0),
            mimeType = mimeType,
            filename = filename,
            extractedText = text?.take(MAX_EXTRACTED_CHARACTERS)
        )
    }.getOrNull()

    /**
     * Stand-in for `PDFDocument.page(at:).string` on iOS. Text is extracted page by page
     * and joined with a blank line so the prompt keeps page boundaries.
     *
     * Returns a descriptive placeholder rather than null when a document is encrypted or
     * has no text layer (a scanned document), so the model is told why the file is empty
     * instead of silently receiving nothing.
     */
    private fun pdfText(context: Context, data: ByteArray, filename: String): String {
        // Defensive: CanopyApplication.onCreate already initialized this, but a PDF
        // arriving via a content provider in an isolated process would not have run it.
        PDFBoxResourceLoader.init(context.applicationContext)

        return try {
            PDDocument.load(ByteArrayInputStream(data)).use { document ->
                if (document.isEncrypted) {
                    return "[PDF attached: $filename. The document is password-protected, " +
                        "so its text could not be read.]"
                }

                val stripper = PDFTextStripper()
                val pages = (1..document.numberOfPages).mapNotNull { pageNumber ->
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    runCatching { stripper.getText(document) }.getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }

                if (pages.isEmpty()) {
                    "[PDF attached: $filename. It has no extractable text layer — it is " +
                        "likely a scan or images only.]"
                } else {
                    pages.joinToString("\n\n")
                }
            }
        } catch (error: Exception) {
            Log.w("CanopyPdf", "PDF text extraction failed for $filename", error)
            "[PDF attached: $filename. Its text could not be read (${error.javaClass.simpleName}).]"
        }
    }

    private fun queryMetadata(context: Context, uri: Uri): Pair<String, Long?> {
        var name = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Attachment"
        var size: Long? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }

    private fun extractedText(
        context: Context,
        data: ByteArray,
        filename: String,
        mimeType: String
    ): String? {
        val extension = filename.substringAfterLast('.', "").lowercase()

        // Stand-in for PDFKit on iOS, which joins per-page text with a blank line.
        // The Android SDK has no text-extracting PDF API, so this uses PdfBox-Android.
        if (mimeType == "application/pdf" || extension == "pdf") {
            return pdfText(context, data, filename)
        }

        if (mimeType.startsWith("text/") || textLikeExtensions.contains(extension)) {
            return runCatching { data.toString(Charsets.UTF_8) }.getOrNull()
                ?: runCatching { data.toString(Charsets.UTF_16) }.getOrNull()
                ?: runCatching { data.toString(Charsets.US_ASCII) }.getOrNull()
        }

        return null
    }
}
