package com.locutus5382.heimdall.recording

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.locutus5382.heimdall.engine.duko.DukoVisualDocument
import com.locutus5382.heimdall.engine.duko.DukoVisualRow
import java.io.IOException
import java.io.OutputStream

/**
 * Offline Android Canvas renderer used identically by PdfDocument and PNG.
 * Never captures the app window (no MediaProjection / game frame capture).
 * Renders a frozen evidence snapshot, with explicit pagination and no
 * silent clipping of warnings or metric rows.
 */
internal object DukoVisualRenderer {
    enum class Format { PDF, PNG }

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    private const val MARGIN = 36f
    private const val CONTENT_TOP = 128f
    private const val CONTENT_BOTTOM = 786f
    private const val MAX_PAGES = 8
    private const val PNG_SCALE = 2f
    private val ink = Color.rgb(240, 245, 255)
    private val secondary = Color.rgb(162, 181, 204)
    private val amber = Color.rgb(242, 181, 81)
    private val coral = Color.rgb(249, 121, 121)
    private val signal = Color.rgb(107, 220, 216)
    private val midnight = Color.rgb(11, 19, 34)
    private val blade = Color.rgb(20, 34, 55)

    private data class TextLine(
        val value: String,
        val fontSize: Float,
        val color: Int,
        val leading: Float,
    )
    private data class Block(val lines: List<TextLine>, val padded: Boolean) {
        val height: Float = lines.sumOf { it.leading.toDouble() }.toFloat() +
            if (padded) 18f else 12f
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else Typeface.create("sans-serif", Typeface.NORMAL)
        }

    /** Wrap by measured glyph widths; no user-supplied report text may overrun. */
    private fun wrap(value: String, fontSize: Float, width: Float): List<String> {
        val measuring = paint(fontSize, ink)
        val wrapped = ArrayList<String>()
        value.lineSequence().forEach { logical ->
            val tokens = logical.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) {
                wrapped += ""
                return@forEach
            }
            var line = ""
            for (token in tokens) {
                // Split extraordinarily long unbroken labels safely.
                var part = token
                while (measuring.measureText(part) > width) {
                    var prefixLength = part.length.coerceAtLeast(1)
                    while (prefixLength > 1 &&
                        measuring.measureText(part.substring(0, prefixLength)) > width)
                        prefixLength--
                    if (line.isNotEmpty()) {
                        wrapped += line
                        line = ""
                    }
                    wrapped += part.substring(0, prefixLength)
                    part = part.substring(prefixLength)
                }
                if (part.isEmpty()) continue
                val candidate = if (line.isEmpty()) part else line + " " + part
                if (measuring.measureText(candidate) <= width) line = candidate
                else {
                    if (line.isNotEmpty()) wrapped += line
                    line = part
                }
            }
            if (line.isNotEmpty()) wrapped += line
        }
        return wrapped
    }

    private fun rowBlock(row: DukoVisualRow): Block {
        val width = PAGE_WIDTH - MARGIN * 2 - 24f
        val title = wrap(row.title, 10f, width)
            .map { TextLine(it, 10f, if (row.caution) coral else signal, 13f) }
        val value = wrap(row.value, 10.3f, width)
            .map { TextLine(it, 10.3f, ink, 14f) }
        val details = wrap(row.explanation, 8.6f, width)
            .map { TextLine(it, 8.6f, secondary, 11.2f) }
        return Block(title + value + details, padded = true)
    }

    private fun sections(document: DukoVisualDocument): List<Block> = buildList {
        for (section in document.sections) {
            add(Block(wrap(section.title, 12f, PAGE_WIDTH - MARGIN * 2)
                .map { TextLine(it, 12f, amber, 18f) }, padded = false))
            for (row in section.rows) add(rowBlock(row))
        }
        add(Block(listOf(TextLine("EVIDENCE LIMITATIONS", 12f, amber, 18f)),
            padded = false))
        for (note in document.caveats) {
            add(Block(wrap(note, 9f, PAGE_WIDTH - MARGIN * 2 - 12f)
                .map { TextLine(it, 9f, secondary, 12f) }, padded = false))
        }
    }

    private fun paginate(document: DukoVisualDocument): List<List<Block>> {
        val pages = ArrayList<MutableList<Block>>()
        var current = ArrayList<Block>()
        var y = CONTENT_TOP
        for (block in sections(document)) {
            if (block.height > CONTENT_BOTTOM - CONTENT_TOP)
                throw IOException("REPORT_BLOCK_EXCEEDS_PAGE")
            if (y + block.height > CONTENT_BOTTOM) {
                if (current.isNotEmpty()) pages += current
                current = ArrayList()
                y = CONTENT_TOP
                if (pages.size >= MAX_PAGES)
                    throw IOException("REPORT_TOO_LONG_FOR_SAFE_EXPORT")
            }
            current += block
            y += block.height + 4f
        }
        if (current.isNotEmpty()) pages += current
        check(pages.isNotEmpty() && pages.size <= MAX_PAGES) { "INVALID_REPORT_PAGE_COUNT" }
        return pages
    }

    private fun drawPage(
        canvas: Canvas,
        report: DukoVisualDocument,
        blocks: List<Block>,
        page: Int,
        pageCount: Int,
    ) {
        canvas.drawColor(midnight)
        canvas.drawText("HEIMDALL / DUKO", MARGIN, 38f, paint(11f, amber, true))
        canvas.drawText(report.title, MARGIN, 67f, paint(20f, ink, true))
        wrap(report.subtitle, 10.5f, PAGE_WIDTH - 2 * MARGIN).take(2)
            .forEachIndexed { i, title ->
                canvas.drawText(title, MARGIN, 89f + i * 14f, paint(10.5f, secondary))
            }
        canvas.drawLine(MARGIN, 111f, PAGE_WIDTH - MARGIN, 111f,
            paint(1f, signal).apply { strokeWidth = 1.5f })
        var y = CONTENT_TOP
        for (block in blocks) {
            if (block.padded) {
                canvas.drawRoundRect(RectF(MARGIN, y, PAGE_WIDTH - MARGIN,
                    y + block.height - 3f), 9f, 9f, paint(1f, blade))
            }
            var baseline = y + if (block.padded) 15f else 13f
            val left = MARGIN + if (block.padded) 12f else 0f
            for (line in block.lines) {
                canvas.drawText(line.value, left, baseline,
                    paint(line.fontSize, line.color, line.color == amber))
                baseline += line.leading
            }
            y += block.height + 4f
        }
        canvas.drawLine(MARGIN, 801f, PAGE_WIDTH - MARGIN, 801f,
            paint(1f, secondary).apply { alpha = 75; strokeWidth = 1f })
        canvas.drawText("Offline report | telemetry, not a gameplay benchmark",
            MARGIN, 817f, paint(8.5f, secondary))
        val pageText = page.toString() + " / " + pageCount
        val footer = paint(8.5f, amber)
        canvas.drawText(pageText, PAGE_WIDTH - MARGIN - footer.measureText(pageText),
            817f, footer)
    }

    fun write(output: OutputStream, report: DukoVisualDocument, format: Format) {
        require(report.sections.isNotEmpty() && report.caveats.isNotEmpty()) {
            "EMPTY_VISUAL_REPORT"
        }
        val pages = paginate(report)
        if (format == Format.PDF) {
            val pdf = PdfDocument()
            try {
                pages.forEachIndexed { index, blocks ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(
                        PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
                    drawPage(page.canvas, report, blocks, index + 1, pages.size)
                    pdf.finishPage(page)
                }
                pdf.writeTo(output)
                output.flush()
            } finally { pdf.close() }
        } else {
            val width = (PAGE_WIDTH * PNG_SCALE).toInt()
            val height = (PAGE_HEIGHT * PNG_SCALE).toInt() * pages.size
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(PNG_SCALE, PNG_SCALE)
                pages.forEachIndexed { index, blocks ->
                    canvas.save()
                    canvas.translate(0f, index.toFloat() * PAGE_HEIGHT)
                    drawPage(canvas, report, blocks, index + 1, pages.size)
                    canvas.restore()
                }
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    throw IOException("PNG_ENCODING_FAILED")
                output.flush()
            } finally { bitmap.recycle() }
        }
    }
}
