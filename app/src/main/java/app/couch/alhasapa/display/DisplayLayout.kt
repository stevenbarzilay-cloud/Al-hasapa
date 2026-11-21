package app.couch.alhasapa.display

import kotlin.math.max

/** Where things go on the screen, in dp, and what the page is told about its viewport. */
data class DisplayLayout(
    /** The WebView's exact size. */
    val webWidth: Float,
    val webHeight: Float,
    /** Width of each side panel (0 = no panels). */
    val panelWidth: Float,
    /** The viewport width the page is told to lay out at, in CSS px; null = leave the page alone (native). */
    val virtualWidth: Int?,
    /** Page scale (CSS px to dp) for that viewport; null when virtualWidth is null. */
    val scale: Float?,
    /** What the page sees: the virtual viewport's height in CSS px. */
    val virtualHeight: Float?,
) {
    val hasPanels: Boolean get() = panelWidth > 0f

    companion object {
        /** Narrower than this and a panel is not readable from the couch, so panels are dropped. */
        const val MIN_PANEL_DP = 150f

        /**
         * @param availWidth/availHeight the room inside the TV-safe margin, in dp
         */
        fun compute(p: DisplayProfile, availWidth: Float, availHeight: Float): DisplayLayout {
            return when (p.mode) {
                DisplayMode.NATIVE -> DisplayLayout(availWidth, availHeight, 0f, null, null, null)
                DisplayMode.WIDE -> {
                    // A desktop-shaped viewport: a phone width left over from another mode does not make sense here.
                    val vwEff = max(100f, (if (p.vw < 600) 1280f else p.vw.toFloat()) * 100f / p.zoom)
                    val scale = availWidth / vwEff
                    DisplayLayout(availWidth, availHeight, 0f, vwEff.toInt(), scale, availHeight / scale)
                }
                DisplayMode.PORTRAIT_FIT -> {
                    // The page is a phone held upright: its box has the aspect of vw x vh, as tall as the screen allows.
                    val aspect = p.vw.toFloat() / p.vh
                    var h = availHeight
                    var w = h * aspect
                    if (w > availWidth) { w = availWidth; h = w / aspect }
                    val vwEff = max(100f, p.vw * 100f / p.zoom)
                    val scale = w / vwEff
                    val leftover = (availWidth - w) / 2f
                    DisplayLayout(w, h, if (leftover >= MIN_PANEL_DP) leftover else 0f, vwEff.toInt(), scale, h / scale)
                }
            }
        }

        /** The TV-safe rectangle: [safeArea] percent of each dimension is kept free on every side. */
        fun safeInsets(p: DisplayProfile, screenWidth: Float, screenHeight: Float): Pair<Float, Float> =
            (screenWidth * p.safeArea.toFloat() / 100f) to (screenHeight * p.safeArea.toFloat() / 100f)
    }
}
