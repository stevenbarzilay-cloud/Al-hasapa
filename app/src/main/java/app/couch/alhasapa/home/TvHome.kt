package app.couch.alhasapa.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.catalog.Catalog
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.scoring.Scoring
import app.couch.alhasapa.tv.LocalTv
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/** One game on the TV's home: what its card shows. */
data class HomeCard(
    val id: String,
    val title: String,
    /** The game's mark (a letter or two, a sign), drawn big on the site's colour. */
    val mark: String,
    val kind: String,
    val description: String,
    val siteName: String,
    val color: Color,
    val played: Boolean,
)

/** Everything the TV's home shows, built from the state, the catalogue and the pieces each time the state changes. */
data class HomeModel(
    val day: String,
    val done: Int,
    val total: Int,
    val points: Int,
    val nameA: String,
    val nameB: String,
    val pieceA: PieceStyle,
    val pieceB: PieceStyle,
    /** The shelf: what is left to play tonight first, then what was played. */
    val tonight: List<HomeCard>,
    /** The rest of the catalogue. */
    val more: List<HomeCard>,
) {
    companion object {
        private val HEBREW = Locale.forLanguageTag("he")

        fun from(state: JSONObject, catalog: Catalog, pieces: PiecesConfig, date: LocalDate): HomeModel {
            val day = CouchState.dayKey(date)
            fun card(g: app.couch.alhasapa.catalog.Game): HomeCard {
                val site = catalog.siteOf(g)
                return HomeCard(
                    id = g.id, title = g.n, mark = g.m, kind = g.k, description = g.d, siteName = site?.name.orEmpty(),
                    color = colorOf(site?.color), played = CouchState.entry(state, day, g.id) != null,
                )
            }
            val (lib, rest) = catalog.games.partition { catalog.inLib(it, state) }
            val shelf = lib.map(::card)
            val (a, b) = CouchState.names(state)
            return HomeModel(
                day = date.format(DateTimeFormatter.ofPattern("EEEE, d 'ב'MMMM", HEBREW)),
                done = shelf.count { it.played },
                total = shelf.size,
                points = Scoring.dayPts(state.optJSONObject("log") ?: JSONObject(), day),
                nameA = a, nameB = b,
                pieceA = pieces.styleFor(state, "a"), pieceB = pieces.styleFor(state, "b"),
                tonight = shelf.filter { !it.played } + shelf.filter { it.played },
                more = rest.map(::card),
            )
        }

        /** "#e3a6ff" -> the colour; anything else -> the app's tint. */
        fun colorOf(hex: String?): Color = runCatching {
            val h = hex!!.trim().removePrefix("#")
            require(h.length == 6)
            Color(0xFF000000 or h.toLong(16))
        }.getOrDefault(Palette.Tint)
    }
}

private val CardWidth = 172.dp
private val CardHeight = 132.dp

/**
 * The TV's home (1.9): the evening and the two of us at the top, then the shelf as a row of big cards, then the rest. Native, so it is
 * there at once (the web hub took four to six seconds to be ready on the TV box) and the remote's arrows move between the cards without
 * a page in between. The card in focus grows and wears a red ring; under the rows, what it is. The full web hub (the links, adding a
 * game, the backup) is one button away, "הגדרות ועוד".
 */
@Composable
fun TvHome(
    model: HomeModel,
    phoneConnected: Boolean,
    onPlay: (String) -> Unit,
    onAccounts: () -> Unit,
    onMore: () -> Unit,
) {
    val tv = LocalTv.current
    val first = model.tonight.firstOrNull() ?: model.more.firstOrNull()
    // The first focus: the first game left to play tonight (the controller's own first pick would be the leftmost, the wrong end here).
    LaunchedEffect(Unit) { if (tv != null && tv.focusedId == null && first != null) tv.focus(cardId(first.id)) }
    val focused = (model.tonight + model.more).firstOrNull { tv != null && tv.isFocused(cardId(it.id)) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize().background(Palette.Cream).verticalScroll(rememberScrollState()).padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(model, phoneConnected)
            RowTitle(stringResource(R.string.home_tonight))
            if (model.tonight.isEmpty()) {
                Text(stringResource(R.string.home_empty), fontSize = 18.sp, color = Palette.Ink2, modifier = Modifier.padding(horizontal = 28.dp))
            } else {
                Cards(model.tonight, onPlay)
            }
            if (model.more.isNotEmpty()) {
                RowTitle(stringResource(R.string.home_more))
                Cards(model.more, onPlay)
            }
            // What the card in focus is (the cards themselves only have room for the name).
            Text(
                focused?.let { "${it.title} · ${it.siteName} · ${it.description}" }.orEmpty(),
                fontSize = 17.sp, color = Palette.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 28.dp).heightIn(min = 44.dp),
            )
            Row(Modifier.padding(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill(stringResource(R.string.home_accounts), onAccounts)
                Pill(stringResource(R.string.home_more_settings), onMore)
            }
        }
    }
}

fun cardId(gameId: String) = "home-$gameId"

@Composable
private fun Header(model: HomeModel, phoneConnected: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.semantics { heading() })
            Text(
                model.day + " · " + stringResource(R.string.home_progress, model.done, model.total) + " · " + stringResource(R.string.panel_points, model.points),
                fontSize = 18.sp, color = Palette.Ink2,
            )
            Text(
                stringResource(if (phoneConnected) R.string.home_phone_on else R.string.home_phone_off),
                fontSize = 15.sp, color = if (phoneConnected) Palette.Red else Palette.Ink2,
            )
        }
        Person(model.pieceA, model.nameA)
        Spacer(Modifier.width(14.dp))
        Person(model.pieceB, model.nameB)
    }
}

@Composable
private fun Person(style: PieceStyle, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PieceIcon(style, 34.dp)
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(horizontal = 28.dp).semantics { heading() })
}

@Composable
private fun Cards(cards: List<HomeCard>, onPlay: (String) -> Unit) {
    // A plain scrolling row, not a lazy one: every card is laid out, so the remote's arrows can reach the ones beyond the edge, and the
    // row scrolls to the card in focus.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        for (c in cards) GameCard(c) { onPlay(c.id) }
    }
}

@Composable
private fun GameCard(card: HomeCard, onClick: () -> Unit) {
    val tv = LocalTv.current
    val id = remember(card.id) { cardId(card.id) }
    val focused = tv?.isFocused(id) == true
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(120), label = "card")
    val played = stringResource(R.string.home_played)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(CardWidth).height(CardHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 10.dp else 0.dp, shape)
            .clip(shape)
            .tvTarget(id, card.title, onClick)
            .background(Palette.Card)
            .border(if (focused) BorderStroke(4.dp, Palette.Red) else BorderStroke(1.dp, Palette.Line2), shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = card.title + if (card.played) ", $played" else ""
            },
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).background(card.color), contentAlignment = Alignment.Center) {
            Text(card.mark, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            if (card.played) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(30.dp).clip(CircleShape).background(Palette.Ink),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Cream) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(card.title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (card.played) played else card.kind, fontSize = 14.sp, color = if (card.played) Palette.Red else Palette.Ink2, maxLines = 1)
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val tv = LocalTv.current
    val id = remember { app.couch.alhasapa.tv.newTvId() }
    val lit = tv?.isFocused(id) == true
    Box(
        Modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Tint)
            .tvTarget(id, label, onClick)                       // in focus it takes the red, like every button of the app
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = if (lit) Palette.Cream else Palette.Ink)
    }
}
