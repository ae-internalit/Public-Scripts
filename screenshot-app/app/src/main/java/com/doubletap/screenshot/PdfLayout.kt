package com.doubletap.screenshot

/**
 * Where an image sits on a PDF page. Kept free of android.graphics so the
 * arithmetic can be tested on the JVM.
 */
data class PageFit(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

object PdfLayout {

    /** A4 at 72dpi, the unit PdfDocument works in. */
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 24

    /**
     * Scales the image to fit inside the page margins without distorting it,
     * and centres it. Images smaller than the printable area are left at their
     * own size rather than blown up, since upscaling a screenshot only makes
     * it blurry.
     */
    fun fit(
        imageWidth: Int,
        imageHeight: Int,
        pageWidth: Int = PAGE_WIDTH,
        pageHeight: Int = PAGE_HEIGHT,
        margin: Int = MARGIN
    ): PageFit {
        require(imageWidth > 0 && imageHeight > 0) { "image must have a size" }

        val availableWidth = (pageWidth - margin * 2).coerceAtLeast(1)
        val availableHeight = (pageHeight - margin * 2).coerceAtLeast(1)

        val scale = minOf(
            availableWidth.toDouble() / imageWidth,
            availableHeight.toDouble() / imageHeight,
            1.0
        )

        val width = (imageWidth * scale).toInt().coerceAtLeast(1)
        val height = (imageHeight * scale).toInt().coerceAtLeast(1)

        return PageFit(
            left = margin + (availableWidth - width) / 2,
            top = margin + (availableHeight - height) / 2,
            width = width,
            height = height
        )
    }
}
