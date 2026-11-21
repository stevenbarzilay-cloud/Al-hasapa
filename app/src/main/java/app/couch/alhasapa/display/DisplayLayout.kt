package app.couch.alhasapa.display

import kotlin.math.max
import kotlin.math.min

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
    /** Extra magnification of the finished picture (1 = none): zoom beyond what the page can re-fit crops the edges. */
    val magnify: Float = 1f,
    /** The panels are narrow: show the essentials only. */
    val compactPanels: Boolean = false,
) {
    val hasPanels: Boolean get() = panelWidth > 0f

    companion object {
        /** Narrower than this and a panel is not readable from the couch, so panels are dropped. */
        const val MIN_PANEL_DP = 150f

        /** Narrower than this (and at least [MIN_PANEL_DP]) and the panels show only the essentials. */
        const val FULL_PANEL_DP = 280f

        /**
         * The most zoom that is done by telling the page its screen is smaller (125% = a screen of 344 px instead of 430): the page
         * lays itself out again, nothing is cut. Past about 140% a phone-sized page starts to break (it is narrower than the
         * smallest phones), so any zoom above this is magnification instead: the picture grows and its edges are cropped.
         */
        const val REFLOW_MAX_ZOOM = 140

        /**
         * @param availWidth/availHeight the room inside the TV-safe margin, in dp
         */
        fun compute(p: DisplayProfile, availWidth: Float, availHeight: Float): DisplayLayout {
            val reflow = min(p.zoom, REFLOW_MAX_ZOOM)
            val magnify = p.zoom.toFloat() / reflow
            return when (p.mode) {
                DisplayMode.NATIVE -> DisplayLayout(availWidth, availHeight, 0f, null, null, null)
                DisplayMode.WIDE -> {
                    // A desktop-shaped viewport: a phone width left over from another mode does not make sense here.
                    val vwEff = max(100f, (if (p.vw < 600) 1280f else p.vw.toFloat()) * 100f / reflow)
                    val scale = availWidth / vwEff
                    DisplayLayout(availWidth, availHeight, 0f, vwEff.toInt(), scale, availHeight / scale, magnify)
                }
                DisplayMode.PORTRAIT_FIT -> {
                    // The page is a phone held upright: its box has the aspect of vw x vh, as tall as the screen allows.
                    val aspect = p.vw.toFloat() / p.vh
                    var h = availHeight
                    var w = h * aspect
                    if (w > availWidth) { w = availWidth; h = w / aspect }
                    val vwEff = max(100f, p.vw * 100f / reflow)
                    val scale = w / vwEff
                    val leftover = (availWidth - w) / 2f
                    val showPanels = p.panels && leftover >= MIN_PANEL_DP
                    DisplayLayout(w, h, if (showPanels) leftover else 0f, vwEff.toInt(), scale, h / scale, magnify, compactPanels = showPanels && leftover < FULL_PANEL_DP)
                }
            }
        }

        /** The TV-safe rectangle: [safeArea] percent of each dimension is kept free on every side. */
        fun safeInsets(p: DisplayProfile, screenWidth: Float, screenHeight: Float): Pair<Float, Float> =
            (screenWidth * p.safeArea.toFloat() / 100f) to (screenHeight * p.safeArea.toFloat() / 100f)
    }
}
