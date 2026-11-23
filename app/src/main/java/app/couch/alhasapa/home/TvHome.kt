package app.couch.alhasapa.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
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
import app.couch.alhasapa.tv.Zone
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
                more = Catalog.offered(rest).map(::card),
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

private val CardWidth = 164.dp
private val CardHeight = 130.dp
private val CardGap = 16.dp
private val Side = 28.dp

/** How far from the row's start the card in focus sits once the row has moved on: enough to show the edge of the card before it. */
private val Peek = 56.dp

const val CARD_PREFIX = "home-"
const val ACCOUNTS_ID = "home:accounts"
const val MORE_ID = "home:more"
const val INFO_ID = "home:info"

fun cardId(gameId: String) = CARD_PREFIX + gameId

/**
 * The TV's home (1.9): the evening and the two of us at the top, then the shelf as a row of big cards, then the rest of the games, then
 * the app's own buttons. Native, so it is there at once (the web hub took four to six seconds to be ready on the TV box). There is no
 * dock here (nothing to point at or type into): the whole width is the games'.
 *
 * The remote moves by the home's shape ([HomeNav]): Left and Right along a row, to its very end; Up and Down between rows, each row
 * keeping its place. The row moves so that the card in focus sits near its start, like the rows of a TV's own home screen, and the card
 * grows and wears a red ring; under the rows, what it is. OK opens the game. After a game was played the focus is on the next one left.
 * (1.9.0 drew every card in a plain row and moved by where buttons are on the screen; a card out of sight is nowhere, so the arrows
 * never got past the fifth card.)
 *
 * The full web hub (the links, adding a game, the backup) is one button away, "הגדרות ועוד".
 */
@Composable
fun TvHome(
    model: HomeModel,
    phoneConnected: Boolean,
    nav: HomeNav,
    /** A connected phone runs another version of the app (see RemoteKeys.Ui): the older one's name ("" unknown), or the newer one's. */
    phoneOlder: String? = null,
    phoneNewer: String? = null,
    onPlay: (String) -> Unit,
    onAccounts: () -> Unit,
    onMore: () -> Unit,
    onInfo: () -> Unit,
) {
    val tv = LocalTv.current
    val tonightIds = remember(model.tonight) { model.tonight.map { cardId(it.id) } }
    val moreIds = remember(model.more) { model.more.map { cardId(it.id) } }
    val pillIds = remember { listOf(ACCOUNTS_ID, MORE_ID, INFO_ID) }
    val ok: (String) -> Unit = { id ->
        when (id) {
            ACCOUNTS_ID -> onAccounts()
            MORE_ID -> onMore()
            INFO_ID -> onInfo()
            else -> onPlay(id.removePrefix(CARD_PREFIX))
        }
    }
    // The home's shape, for the remote: read when a key comes, so it is simply kept up to date.
    SideEffect { nav.rows = listOf(tonightIds, moreIds, pillIds); nav.onOk = ok }
    if (tv != null) {
        DisposableEffect(tv, nav) {
            nav.rows = listOf(tonightIds, moreIds, pillIds); nav.onOk = ok
            tv.screenNav = nav
            tv.dockHidden = true
            onDispose { if (tv.screenNav === nav) tv.screenNav = null; tv.dockHidden = false }
        }
    }
    val focusedId = tv?.focusedId?.takeIf { tv.zone == Zone.NATIVE }
    val panel = tv?.panel
    // Never without a focus while the home is in front: when it opens, when the information panel closes, back from the hub.
    LaunchedEffect(focusedId, panel, tonightIds, moreIds) {
        if (tv != null && panel == null && nav.position(tv.focusedId) == null) nav.home()?.let { tv.focus(it) }
    }
    LaunchedEffect(focusedId) { nav.note(focusedId) }
    // A game was played (the shelf puts it after the ones still left): the focus goes on to the next one left.
    var doneSeen by remember { mutableIntStateOf(model.done) }
    LaunchedEffect(model.done) {
        if (tv != null && model.done > doneSeen) tonightIds.firstOrNull()?.let { tv.focus(it) }
        doneSeen = model.done
    }
    val focusRow = nav.position(focusedId)?.first
    val focused = remember(focusedId, model) { (model.tonight + model.more).firstOrNull { cardId(it.id) == focusedId } }
    // Everything fits a TV screen; on a smaller window the page scrolls to the row in focus.
    val sections = remember { List(3) { BringIntoViewRequester() } }
    LaunchedEffect(focusRow) { if (focusRow != null) sections[focusRow].bringIntoView() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier.fillMaxSize().background(Palette.Cream).verticalScroll(rememberScrollState()).padding(top = 18.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Header(model)
            Column(Modifier.bringIntoViewRequester(sections[0])) {
                RowTitle(stringResource(R.string.home_tonight))
                if (model.tonight.isEmpty()) {
                    Text(stringResource(R.string.home_empty), fontSize = 18.sp, color = Palette.Ink2, modifier = Modifier.padding(horizontal = Side, vertical = 12.dp))
                } else {
                    Cards(model.tonight, tonightIds.indexOf(focusedId), onPlay)
                }
            }
            if (model.more.isNotEmpty()) {
                Column(Modifier.bringIntoViewRequester(sections[1])) {
                    RowTitle(stringResource(R.string.home_more))
                    Cards(model.more, moreIds.indexOf(focusedId), onPlay)
                }
            }
            // What the card in focus is (the cards themselves only have room for the name).
            Text(
                focused?.let { "${it.title} · ${it.siteName} · ${it.description}" }.orEmpty(),
                fontSize = 17.sp, color = Palette.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Side).heightIn(min = 24.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Side).bringIntoViewRequester(sections[2]),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(ACCOUNTS_ID, stringResource(R.string.home_accounts), onAccounts)
                Pill(MORE_ID, stringResource(R.string.home_more_settings), onMore)
                Pill(INFO_ID, stringResource(R.string.tv_info_title), onInfo)
                Spacer(Modifier.width(4.dp))
                // The phone's app and this one are built together: when one of them was left behind, say which (it is easy to miss).
                val line = when {
                    phoneConnected && phoneOlder != null ->
                        if (phoneOlder.isEmpty()) stringResource(R.string.home_phone_old) else stringResource(R.string.home_phone_old_named, phoneOlder)
                    phoneConnected && phoneNewer != null -> stringResource(R.string.home_phone_newer, phoneNewer)
                    phoneConnected -> stringResource(R.string.home_phone_on)
                    else -> stringResource(R.string.home_phone_off)
                }
                Text(
                    line, fontSize = 15.sp, color = if (phoneConnected) Palette.Red else Palette.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (phoneConnected && (phoneOlder != null || phoneNewer != null)) FontWeight.Bold else FontWeight.Normal,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Header(model: HomeModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Side), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.semantics { heading() })
            Text(
                model.day + " · " + stringResource(R.string.home_progress, model.done, model.total) + " · " + stringResource(R.string.panel_points, model.points),
                fontSize = 17.sp, lineHeight = 22.sp, color = Palette.Ink2, maxLines = 1,
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
    Text(text, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(horizontal = Side).semantics { heading() })
}

/**
 * One row of cards. Only the cards near the screen exist (a lazy row: the shelf and the rest are some sixty cards, a lot for a TV box
 * to draw at once), and the row itself moves to the card in focus ([focusIndex], -1 when the focus is in another row).
 */
@Composable
private fun Cards(cards: List<HomeCard>, focusIndex: Int, onPlay: (String) -> Unit) {
    val state = rememberLazyListState()
    val peek = with(LocalDensity.current) { Peek.roundToPx() }
    LaunchedEffect(focusIndex) {
        if (focusIndex >= 0) state.animateScrollToItem(focusIndex, if (focusIndex == 0) 0 else -peek)
    }
    // No keys: when a played game moves to the end of the shelf, the row stays where it is and the next game takes its place.
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Side, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        items(cards.size) { i -> GameCard(cards[i]) { onPlay(cards[i].id) } }
    }
}

@Composable
private fun GameCard(card: HomeCard, onClick: () -> Unit) {
    val tv = LocalTv.current
    val id = cardId(card.id)
    val focused = tv?.isFocused(id) == true
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(120), label = "card")
    val played = stringResource(R.string.home_played)
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .width(CardWidth).height(CardHeight)
            // One layer a card (its size, its shadow and its round corners together): a third of what the box had to compose before.
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (focused) 10.dp.toPx() else 0f; this.shape = shape; clip = true }
            .tvTarget(id, card.title, bring = false, onClick = onClick)        // the row moves to the card itself (see Cards)
            .background(Palette.Card)
            .border(if (focused) BorderStroke(4.dp, Palette.Red) else BorderStroke(1.dp, Palette.Line2), shape)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = card.title + if (card.played) ", $played" else ""
            },
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).background(card.color), contentAlignment = Alignment.Center) {
            Text(card.mark, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
            if (card.played) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(8.dp).size(28.dp).clip(CircleShape).background(Palette.Ink),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Cream) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(card.title, fontSize = 17.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (card.played) played else card.kind, fontSize = 14.sp, lineHeight = 18.sp, color = if (card.played) Palette.Red else Palette.Ink2, maxLines = 1)
        }
    }
}

@Composable
private fun Pill(id: String, label: String, onClick: () -> Unit) {
    val tv = LocalTv.current
    val lit = tv?.isFocused(id) == true
    Box(
        Modifier
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Tint)
            .tvTarget(id, label, onClick)                       // in focus it takes the red, like every button of the app
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp))
            .padding(horizontal = 20.dp)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (lit) Palette.Cream else Palette.Ink, maxLines = 1)
    }
}
