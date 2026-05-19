package com.ghoneim.flutter_pdf_annotations

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.io.StringWriter

/**
 * Extracts per-glyph text positions from a PDF so highlight mode can snap to
 * text the way iOS's PDFKit `page.selection(for:)` does.
 *
 * iOS receives a rectangle from the user, asks PDFKit for the text selection
 * intersecting that rect, splits by line, and creates one highlight annotation
 * per line tight to glyph bounds. We do the same here by:
 *   1) collecting every glyph's PDF-space bounding box via a custom
 *      [PDFTextStripper] subclass (called lazily on first highlight per page),
 *   2) intersecting them with the user's drag rect,
 *   3) clustering intersecting glyphs by line and returning one tight rect per
 *      line. Empty result → caller should fall back to the raw drag rect (also
 *      matches iOS behavior on scanned PDFs without an embedded text layer).
 *
 * All coordinates exchanged through public methods are in PDF user-space
 * points with **top-left origin** (Android convention) — callers don't have to
 * deal with PDF's bottom-left origin. The conversion happens internally.
 *
 * Owns the PDDocument it loads; call [close] when done.
 */
internal class PdfTextExtractor(private val pdfFile: File) {

    private var document: PDDocument? = null

    /** Cached glyph boxes per 0-based page index. Lazily populated. */
    private val glyphCache = HashMap<Int, List<GlyphBox>>()

    /**
     * Bounding box of a single glyph in PDF user-space points, top-left origin
     * (Android convention). `lineKey` groups glyphs that share a baseline so
     * the caller can produce one rect per visual line.
     */
    private data class GlyphBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val lineKey: Int,
    )

    /**
     * Open the document. Safe to call multiple times; only the first load
     * actually parses the file.
     */
    fun open() {
        if (document == null) {
            document = PDDocument.load(pdfFile)
        }
    }

    fun close() {
        try { document?.close() } catch (_: Exception) {}
        document = null
        glyphCache.clear()
    }

    /**
     * Snap the user's drag rect to underlying text on [pageIndex] (0-based).
     *
     * @param dragRectPdfPoints the user's drag in PDF user-space points,
     *   top-left origin.
     * @return one tight rect per line of text intersected, in the same
     *   coordinate space. Empty if no text intersects (caller should fall back
     *   to highlighting the raw drag rect — matches iOS).
     */
    fun snapToTextLines(pageIndex: Int, dragRectPdfPoints: RectF): List<RectF> {
        val glyphs = glyphsFor(pageIndex)
        if (glyphs.isEmpty()) return emptyList()

        // Treat zero-height/width drags defensively — a tap shouldn't highlight a whole page.
        if (dragRectPdfPoints.width() < 0.5f || dragRectPdfPoints.height() < 0.5f) {
            return emptyList()
        }

        // Group intersecting glyphs by visual line.
        val byLine = HashMap<Int, MutableList<GlyphBox>>()
        for (g in glyphs) {
            if (g.right < dragRectPdfPoints.left) continue
            if (g.left > dragRectPdfPoints.right) continue
            if (g.bottom < dragRectPdfPoints.top) continue
            if (g.top > dragRectPdfPoints.bottom) continue
            byLine.getOrPut(g.lineKey) { mutableListOf() }.add(g)
        }

        if (byLine.isEmpty()) return emptyList()

        // One rect per line, tight to the glyphs we kept. Sort by top so visual
        // order is preserved in the resulting list / on the undo stack.
        return byLine.values
            .map { line ->
                var left = Float.POSITIVE_INFINITY
                var top = Float.POSITIVE_INFINITY
                var right = Float.NEGATIVE_INFINITY
                var bottom = Float.NEGATIVE_INFINITY
                for (g in line) {
                    if (g.left < left) left = g.left
                    if (g.top < top) top = g.top
                    if (g.right > right) right = g.right
                    if (g.bottom > bottom) bottom = g.bottom
                }
                RectF(left, top, right, bottom)
            }
            .sortedBy { it.top }
    }

    private fun glyphsFor(pageIndex: Int): List<GlyphBox> {
        glyphCache[pageIndex]?.let { return it }

        val doc = document ?: return emptyList()
        if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return emptyList()

        val collector = GlyphCollector()
        collector.startPage = pageIndex + 1 // pdfbox is 1-based
        collector.endPage = pageIndex + 1
        collector.sortByPosition = true

        try {
            // writeText drives the stripper; we discard the StringWriter and
            // keep the side-effect (collector.glyphs).
            collector.writeText(doc, StringWriter())
        } catch (_: Exception) {
            // Treat any extraction failure as "no text on this page" — caller
            // falls back to raw-rect highlighting. Matches iOS's behavior on
            // scanned PDFs without a text layer.
            glyphCache[pageIndex] = emptyList()
            return emptyList()
        }

        val result = collector.glyphs.toList()
        glyphCache[pageIndex] = result
        return result
    }

    /**
     * PDFTextStripper subclass that captures every glyph's bounding box.
     *
     * `TextPosition.getYDirAdj()` already returns a top-left-origin Y in
     * PDF user-space points (PDFBox flips PDF's native bottom-left origin
     * for us), so we don't need to know the page height here.
     */
    private class GlyphCollector : PDFTextStripper() {
        val glyphs = mutableListOf<GlyphBox>()

        // Two TextPosition baselines are considered the same line when their
        // y-distance is < lineTolerance * heightDir. 0.5 is the same threshold
        // PDFBox uses internally for sortByPosition line breaks.
        private val lineTolerance = 0.5f

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                if (tp.unicode.isNullOrEmpty()) continue
                if (tp.unicode.all { it.isWhitespace() }) continue

                val height = tp.heightDir
                if (height <= 0f) continue

                val left = tp.xDirAdj
                val top = tp.yDirAdj - height // yDirAdj is the baseline; glyph top sits above it
                val right = left + tp.widthDirAdj
                val bottom = tp.yDirAdj

                // Bucket the baseline into integer line keys so glyphs on the
                // same visual line group together even with slight float drift.
                val lineKey = (tp.yDirAdj / (height * lineTolerance + 0.0001f)).toInt()

                glyphs.add(GlyphBox(left, top, right, bottom, lineKey))
            }
            // Don't delegate to super — we only care about the side effect.
        }
    }
}
