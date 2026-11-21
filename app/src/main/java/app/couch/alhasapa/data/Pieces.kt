package app.couch.alhasapa.data

import android.content.Context
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

/** One person's piece as the native screens draw it: a shape and the three tones of its colour (see assets/hub/pieces.json). */
data class PieceStyle(
    val shapeId: String,
    val colorId: String,
    val shapePath: String,
    val shapeName: String,
    val colorName: String,
    val base: Color,
    val accent: Color,
    val soft: Color,
)

/** The shared catalogue of shapes and colours (the same file the hub uses) and the person's choices from the state. */
class PiecesConfig private constructor(private val doc: JSONObject) {
    private fun shape(id: String): JSONObject? = (0 until doc.getJSONArray("shapes").length()).map { doc.getJSONArray("shapes").getJSONObject(it) }.firstOrNull { it.getString("id") == id }
    private fun color(id: String): JSONObject? = (0 until doc.getJSONArray("colors").length()).map { doc.getJSONArray("colors").getJSONObject(it) }.firstOrNull { it.getString("id") == id }

    /** [who] is "a" (the first person: red circle by default) or "b" (the second: pink triangle). */
    fun styleFor(state: JSONObject?, who: String): PieceStyle {
        val defaults = doc.getJSONObject("defaults").getJSONObject(who)
        val chosen = state?.optJSONObject("pieces")?.optJSONObject(who)
        val s = shape(chosen?.optString("shape").orEmpty()) ?: shape(defaults.getString("shape"))!!
        val c = color(chosen?.optString("color").orEmpty()) ?: color(defaults.getString("color"))!!
        return PieceStyle(
            shapeId = s.getString("id"), colorId = c.getString("id"), shapePath = s.getString("d"),
            shapeName = s.getString("n"), colorName = c.getString("n"),
            base = hex(c.getString("base")), accent = hex(c.getString("accent")), soft = hex(c.getString("soft")),
        )
    }

    companion object {
        fun parse(json: String) = PiecesConfig(JSONObject(json))

        fun load(context: Context): PiecesConfig =
            parse(context.assets.open("hub/pieces.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

        fun hex(h: String): Color = Color(0xFF000000L or h.removePrefix("#").toLong(16))
    }
}
