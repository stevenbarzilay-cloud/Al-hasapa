package app.couch.alhasapa.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.PathParser
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.ui.theme.Palette

/** A person's piece, drawn exactly like the hub draws it: the shape on a 20x20 grid, filled, with an ink outline. */
@Composable
fun PieceIcon(style: PieceStyle, size: Dp, modifier: Modifier = Modifier, label: String? = null) {
    val path = remember(style.shapePath) { PathParser.createPathFromPathData(style.shapePath).asComposePath() }
    val description = label ?: "${style.shapeName} ${style.colorName}"
    Canvas(modifier.size(size).semantics { contentDescription = description }) {
        scale(this.size.minDimension / 20f, pivot = Offset.Zero) {
            drawPath(path, style.base)
            drawPath(path, Palette.Ink, style = Stroke(width = 1.5f, join = StrokeJoin.Round))
        }
    }
}
