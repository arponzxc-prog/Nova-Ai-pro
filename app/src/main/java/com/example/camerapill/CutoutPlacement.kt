package com.example.camerapill

/** Android-free rectangle so placement logic is unit-testable on the JVM. */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * Overlay window position plus the horizontal offset the pill content needs inside the window
 * so it stays centred on the camera cutout even when the window had to be clamped to the screen.
 */
data class PillWindowPosition(val x: Int, val y: Int, val contentOffsetX: Int, val anchoredToCutout: Boolean)

object CutoutPlacement {

    /**
     * @param cutouts bounding rects from DisplayCutout (empty on devices/APIs without a cutout)
     * @param statusBarTop status bar height in px (fallback anchor when there is no cutout)
     * @param collapsedWidth/collapsedHeight size of the compact pill in px
     * @param windowWidth/windowHeight overlay window size (fits the expanded pill) in px
     */
    fun compute(
        screenWidth: Int,
        screenHeight: Int,
        cutouts: List<PxRect>,
        statusBarTop: Int,
        collapsedWidth: Int,
        collapsedHeight: Int,
        windowWidth: Int,
        windowHeight: Int,
        gapPx: Int,
        minTopPx: Int
    ): PillWindowPosition {
        val edge = cutouts
            .filter { it.width > 0 && it.height > 0 }
            .filter { it.top <= 1 || it.left <= 1 || it.right >= screenWidth - 1 }
            .minByOrNull { it.top + it.left.coerceAtLeast(0) / 4 }

        val centerX: Int
        val pillTop: Int
        var anchored = true

        when {
            edge == null -> {
                anchored = false
                centerX = screenWidth / 2
                pillTop = ((statusBarTop - collapsedHeight) / 2).coerceAtLeast(minTopPx)
            }
            // Top-edge cutout (notch / centred or corner punch-hole in portrait)
            edge.top <= 1 && edge.width < screenWidth * 0.9f && edge.centerY < screenHeight / 3 -> {
                centerX = edge.centerX
                pillTop = (edge.centerY - collapsedHeight / 2).coerceAtLeast(0)
            }
            // Cutout on the left edge (landscape)
            edge.left <= 1 && edge.height < screenHeight * 0.9f -> {
                centerX = edge.right + gapPx + collapsedWidth / 2
                pillTop = (edge.centerY - collapsedHeight / 2).coerceAtLeast(0)
            }
            // Cutout on the right edge (landscape)
            edge.right >= screenWidth - 1 && edge.height < screenHeight * 0.9f -> {
                centerX = edge.left - gapPx - collapsedWidth / 2
                pillTop = (edge.centerY - collapsedHeight / 2).coerceAtLeast(0)
            }
            else -> {
                anchored = false
                centerX = screenWidth / 2
                pillTop = ((statusBarTop - collapsedHeight) / 2).coerceAtLeast(minTopPx)
            }
        }

        val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
        val idealX = centerX - windowWidth / 2
        val x = idealX.coerceIn(0, maxX)
        val y = pillTop.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))
        return PillWindowPosition(x = x, y = y, contentOffsetX = centerX - (x + windowWidth / 2), anchoredToCutout = anchored)
    }
}
