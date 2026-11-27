package app.couch.alhasapa.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One game on the TV's home: what its cushion shows, and what is said about it while it is in focus. */
data class HomeCard(
    val id: String,
    val title: String,
    /** What kind of game it is, in words: the catalogue's own, or the symbol's own name where the catalogue has none (the film games). */
    val kind: String,
    val description: String,
    val siteName: String,
    val played: Boolean,
    /** Which symbol the cushion wears. */
    val category: Category,
    /** The cushion's fabric, print and colours: from the game's id and the two people's colours, so it is the same wherever it stands. */
    val look: CushionLook,
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
    /** The rest of the catalogue: the suggestions we picked first, in their order. */
    val more: List<HomeCard>,
    /** The wall behind the shelves: cream with a breath of the two people's colours. */
    val wall: Color,
    /** The couch this TV is of, by name (2.0): said at the top of its home, so a guest knows whose couch they sit on. */
    val couch: String = "",
) {
    companion object {
        private val HEBREW = Locale.forLanguageTag("he")

        fun from(state: JSONObject, catalog: Catalog, pieces: PiecesConfig, date: LocalDate): HomeModel {
            val day = CouchState.dayKey(date)
            val pieceA = pieces.styleFor(state, "a")
            val pieceB = pieces.styleFor(state, "b")
            // The cushions are made here, once for a state: a press of the remote only moves the focus's thread from one to the next.
            fun card(g: app.couch.alhasapa.catalog.Game): HomeCard {
                val category = Category.of(g.k, g.cat)
                return HomeCard(
                    id = g.id, title = g.n, kind = category.ownLabel?.takeIf { g.cat == category.id } ?: g.k, description = g.d,
                    siteName = catalog.siteOf(g)?.name.orEmpty(), played = CouchState.entry(state, day, g.id) != null,
                    category = category, look = CushionColors.look(g.id, pieceA.base, pieceB.base),
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
                nameA = a, nameB = b, couch = app.couch.alhasapa.data.Couches.name(state),
                pieceA = pieceA, pieceB = pieceB,
                tonight = shelf.filter { !it.played } + shelf.filter { it.played },
                more = Catalog.offered(rest).map(::card),
                wall = CushionColors.wall(pieceA.base, pieceB.base),
            )
        }
    }
}

/** The margin at the sides: the logo, the names, the picture, the titles and the first cushion all start from it. */
private val Side = 48.dp

/** A cushion: the demo's box of 232 by 150, at the size at which five and a bit stand side by side on a TV. */
private val CushionHeight = 90.dp
private val CushionWidth = CushionHeight * (CushionArt.W / CushionArt.H)

/** The room around a cushion for the threads it wears in focus, and the space between two cushions. */
private val Around = 5.dp
private val Between = 18.dp

/** A shelf: a little air over the cushions, the cushions, and the plank they stand on. */
private val ShelfTop = 3.dp
private val ShelfHeight = ShelfTop + CushionHeight + Around * 2 + 5.dp

private val LogoHeight = 38.dp
private val PictureHeight = 110.dp

const val CARD_PREFIX = "home-"
const val ACCOUNTS_ID = "home:accounts"
const val MORE_ID = "home:more"
const val INFO_ID = "home:info"
const val SETTINGS_ID = "home:tv-settings"

/** For the tests: the name, the kind and the words about what is in focus. */
const val DETAIL_TITLE = "home:detail-title"
const val DETAIL_KICKER = "home:detail-kicker"
const val DETAIL_SUMMARY = "home:detail-summary"

fun cardId(gameId: String) = CARD_PREFIX + gameId

/** What the top of the home says about the button in focus. */
private data class Detail(val kicker: String, val title: String, val summary: String)

/**
 * The TV's home. Native, so it is there at once (the web hub took four to six seconds to be ready on the TV box), with no dock
 * (nothing here to point at or type into): the whole width is the games'.
 *
 * How it looks (1.9.5; until then it was plain cards, pared down to run well on the box): our logo at the top right, the two of us
 * and our pieces at the top left with the picture of us on the couch under the names, and at its side what the button in focus is.
 * Under that the games, each a cushion on a shelf: the shelf of the evening, then the rest. At the foot the app's own buttons and
 * the line about the phone. The look came as a demo page; the cushions are drawn here from its shapes (see Cushion.kt), the logo and
 * the picture are the app's original ones (see HomeArt).
 *
 * What did not change with the look: the remote moves by the home's shape ([HomeNav]): Left and Right along a shelf, to its very end;
 * Up and Down between shelves and the buttons, each row keeping its place. The shelf moves so that the cushion in focus stands at its
 * start, like the rows of a TV's own home screen, with a sliver of the one before it. OK opens the game. After a game was played the
 * focus is on the next one left. Only the cushions near the screen exist (a lazy row). (1.9.0 drew every card in a plain row and
 * moved by where buttons are on the screen; a card out of sight is nowhere, so the arrows never got past the fifth card.)
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
    /** The TV's own settings: the picture's size, the writing's, the cursor (see TvSettingsPanel). */
    onSettings: () -> Unit = {},
) {
    val tv = LocalTv.current
    val tonightIds = remember(model.tonight) { model.tonight.map { cardId(it.id) } }
    val moreIds = remember(model.more) { model.more.map { cardId(it.id) } }
    val pillIds = remember { listOf(ACCOUNTS_ID, MORE_ID, SETTINGS_ID, INFO_ID) }
    val ok: (String) -> Unit = { id ->
        when (id) {
            ACCOUNTS_ID -> onAccounts()
            MORE_ID -> onMore()
            INFO_ID -> onInfo()
            SETTINGS_ID -> onSettings()
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

    // What is said at the top about the button in focus: a game, or one of the app's own buttons. While the focus is somewhere that
    // is not the home's (the information panel's own button), what was said last stays.
    val played = stringResource(R.string.home_played)
    val appName = stringResource(R.string.app_name)
    val abouts = mapOf(
        ACCOUNTS_ID to Detail(appName, stringResource(R.string.home_accounts), stringResource(R.string.home_about_accounts)),
        MORE_ID to Detail(appName, stringResource(R.string.home_more_settings), stringResource(R.string.home_about_more)),
        INFO_ID to Detail(appName, stringResource(R.string.tv_info_title), stringResource(R.string.home_about_info)),
        SETTINGS_ID to Detail(appName, stringResource(R.string.home_tv_settings), stringResource(R.string.home_about_tv_settings)),
    )
    val said = remember { arrayOfNulls<Detail>(1) }
    val now = remember(focusedId, model) {
        val card = (model.tonight + model.more).firstOrNull { cardId(it.id) == focusedId } ?: if (focusedId == null) model.tonight.firstOrNull() else null
        card?.let { Detail(listOf(it.kind, it.siteName, if (it.played) played else "").filter(String::isNotEmpty).joinToString(" · "), it.title, it.description) }
            ?: abouts[focusedId]
    }
    if (now != null) said[0] = now                      // only remembered: writing it starts nothing over
    val detail = now ?: said[0]

    // Everything fits a TV screen; on a smaller window the page scrolls to the row in focus.
    val sections = remember { List(3) { BringIntoViewRequester() } }
    LaunchedEffect(focusRow) { if (focusRow != null) sections[focusRow].bringIntoView() }
    val wall = model.wall
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            Modifier
                .fillMaxSize()
                // The wall: cream at the left, warming towards the two of us's colours at the right.
                .drawBehind { drawRect(Brush.horizontalGradient(0f to WallLight, 0.8f to wall, 1f to wall)) }
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp, bottom = 12.dp),
        ) {
            Masthead(model)
            Hero(detail, model)
            Column(Modifier.bringIntoViewRequester(sections[0])) {
                ShelfTitle(
                    stringResource(R.string.home_tonight),
                    (if (model.couch.isNotEmpty()) model.couch + " · " else "") + model.day + " · " + stringResource(R.string.home_progress, model.done, model.total) + " · " + stringResource(R.string.panel_points, model.points),
                )
                if (model.tonight.isEmpty()) {
                    Text(stringResource(R.string.home_empty), fontSize = 18.sp, color = Palette.Ink2, modifier = Modifier.padding(horizontal = Side, vertical = 12.dp))
                } else {
                    Shelf(model.tonight, tonightIds.indexOf(focusedId), onPlay)
                }
            }
            if (model.more.isNotEmpty()) {
                Column(Modifier.bringIntoViewRequester(sections[1])) {
                    ShelfTitle(stringResource(R.string.home_more), null)
                    Shelf(model.more, moreIds.indexOf(focusedId), onPlay)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Side).padding(top = 12.dp).bringIntoViewRequester(sections[2]),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill(ACCOUNTS_ID, stringResource(R.string.home_accounts), onAccounts)
                Pill(MORE_ID, stringResource(R.string.home_more_settings), onMore)
                Pill(SETTINGS_ID, stringResource(R.string.home_tv_settings), onSettings)
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
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val WallLight = Color(0xFFF9EFDF)
private val Hairline = Color(0xFFAD8B7A)

/** The top line: our logo at the right, the two of us and our pieces at the left, and a hairline under both. */
@Composable
private fun Masthead(model: HomeModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Side).height(LogoHeight), verticalAlignment = Alignment.CenterVertically) {
        Picture(HomeArt.Picture.LOGO, LogoHeight, stringResource(R.string.app_name), Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        Person(model.pieceA, model.nameA)
        Spacer(Modifier.width(16.dp))
        Person(model.pieceB, model.nameB)
    }
    Box(Modifier.padding(horizontal = Side).padding(top = 5.dp).fillMaxWidth().height(1.dp).background(Hairline))
}

@Composable
private fun Person(style: PieceStyle, name: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PieceIcon(style, 26.dp)
        Text(name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}

/**
 * Under the top line: at the right what the button in focus is (its kind and site, its name, a sentence about it), and at the left,
 * under the names and starting at the same margin as they do, the picture of the two of us on the couch.
 */
@Composable
private fun Hero(detail: Detail?, model: HomeModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Side).padding(top = 4.dp).height(PictureHeight), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 28.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                detail?.kicker.orEmpty(), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink2, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth().testTag(DETAIL_KICKER),
            )
            Text(
                detail?.title.orEmpty(), fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1,
                overflow = TextOverflow.Ellipsis, letterSpacing = (-0.02).em, textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth().testTag(DETAIL_TITLE),
            )
            Text(
                detail?.summary.orEmpty(), fontSize = 18.sp, lineHeight = 23.sp, color = Palette.Ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Right, modifier = Modifier.fillMaxWidth().testTag(DETAIL_SUMMARY),
            )
        }
        Picture(HomeArt.Picture.COUPLE, PictureHeight, stringResource(R.string.home_couple, model.nameA, model.nameB), pieces = model.pieceA to model.pieceB)
    }
}

/**
 * One of the home's two pictures, [height] tall and as wide as the drawing in it. Its place is kept from the first frame; the picture
 * itself is read off the main thread and appears when it is ready (at once, when the app read it while starting: see [warmHomeArt]).
 */
@Composable
private fun Picture(
    picture: HomeArt.Picture, height: Dp, description: String, modifier: Modifier = Modifier,
    /** The two people's pieces, for a picture in their colours (1.12); null: as drawn. */
    pieces: Pair<app.couch.alhasapa.data.PieceStyle, app.couch.alhasapa.data.PieceStyle>? = null,
) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { height.roundToPx() }
    val colours = pieces?.let { it.first.colorId + "|" + it.second.colorId }
    val art by produceState(HomeArt.cached(picture), picture, colours) {
        if (value == null) value = withContext(Dispatchers.IO) { HomeArt.load(context, picture, px) }
        if (pieces != null) withContext(Dispatchers.IO) { HomeArt.tinted(context, picture, px, pieces.first, pieces.second) }?.let { value = it }
    }
    val aspect = art?.aspect ?: picture.aspect
    Canvas(modifier.height(height).width(height * aspect).semantics { contentDescription = description; role = Role.Image }) {
        val a = art ?: return@Canvas
        drawImage(a.image, srcOffset = a.bounds.topLeft, srcSize = a.bounds.size, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
    }
}

/** Reads the home's two pictures ahead of the first frame, at the size the home draws them. Call off the main thread. */
fun warmHomeArt(context: android.content.Context) {
    val density = context.resources.displayMetrics.density
    HomeArt.load(context, HomeArt.Picture.LOGO, (LogoHeight.value * density).roundToInt())
    HomeArt.load(context, HomeArt.Picture.COUPLE, (PictureHeight.value * density).roundToInt())
}

/** A shelf's name at the right and, for the evening's shelf, the day and the count at the left. */
@Composable
private fun ShelfTitle(text: String, meta: String?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Side).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.weight(1f))
        if (meta != null) Text(meta, fontSize = 15.sp, lineHeight = 24.sp, color = Palette.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One shelf: the plank, which stays where it is, and on it the row of cushions, which moves. Only the cushions near the screen exist
 * (a lazy row: the two shelves hold some seventy games), and the row moves to the cushion in focus ([focusIndex], -1 when the focus is
 * on another row), which then stands at the row's start with a sliver of the one before it showing.
 */
@Composable
private fun Shelf(cards: List<HomeCard>, focusIndex: Int, onPlay: (String) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(focusIndex) {
        if (focusIndex >= 0) state.animateScrollToItem(focusIndex)
    }
    Box(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(ShelfHeight).drawBehind { plank() }) {
        // No keys: when a played game moves to the end of the shelf, the row stays where it is and the next game takes its place.
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = Side - Around, end = Side - Around, top = ShelfTop),
            horizontalArrangement = Arrangement.spacedBy(Between - Around * 2),
        ) {
            items(cards.size) { i -> Cushion(cards[i]) { onPlay(cards[i].id) } }
        }
    }
}

private val PlankTop = Color(0xFFBC8F74)
private val PlankFace = Color(0xFFEDC3A0)
private val PlankEdge = Color(0xFF947462)
private val PlankFront = Color(0xFFAB795F)
private val PlankUnder = Color(0xFF87624E)
private val PlankShadow = Color(0x3049271F)

/** The plank, across the whole width, at the foot of the shelf's box: its top face, its front edge, and a little shadow under it. */
private fun DrawScope.plank() {
    val face = 15.dp.toPx(); val lift = 4.6f.dp.toPx(); val front = 5.dp.toPx(); val line = 1.dp.toPx()
    val top = size.height - lift - face
    drawRect(Brush.verticalGradient(listOf(PlankTop, PlankFace), startY = top, endY = top + face), topLeft = Offset(0f, top), size = Size(size.width, face))
    drawRect(PlankEdge, topLeft = Offset(0f, top), size = Size(size.width, line))
    drawRect(PlankFront, topLeft = Offset(0f, size.height - front), size = Size(size.width, front))
    drawRect(PlankUnder, topLeft = Offset(0f, size.height - line), size = Size(size.width, line))
    val shadow = 4.dp.toPx()
    drawRect(Brush.verticalGradient(listOf(PlankShadow, Color.Transparent), startY = size.height, endY = size.height + shadow), topLeft = Offset(0f, size.height), size = Size(size.width, shadow))
}

/**
 * A game, as a cushion: the same button it always was (the same id for the remote, the same press), with the drawing inside it.
 * Straight, the same size as every other, also in focus: the focus is the thread around its edge.
 *
 * The cushion is drawn once into a picture of its own, which the box then only has to move while the shelf moves. The focus's thread
 * is not part of that picture: it is drawn under it (the focus is looked at while drawing, so nothing is composed again), and moving
 * the focus costs three lines around two cushions, whatever is on them.
 */
@Composable
private fun Cushion(card: HomeCard, onClick: () -> Unit) {
    val tv = LocalTv.current
    val id = cardId(card.id)
    val played = stringResource(R.string.home_played)
    // A long name gets two lines of smaller letters; the whole of it is in the description for a screen reader, and at the top of
    // the home while the cushion is in focus.
    val long = card.title.length > 14
    Box(
        Modifier
            .size(CushionWidth + Around * 2, CushionHeight + Around * 2)
            .drawBehind {
                if (tv?.isFocused(id) == true) {
                    val around = Around.toPx()
                    with(CushionArt) { threads(card.look.outline, Offset(around, around), CushionWidth.toPx() / CushionArt.W) }
                }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .tvTarget(id, card.title, bring = false, paint = false, onClick = onClick)       // the shelf moves to the cushion itself (see Shelf)
            .drawBehind {
                val around = Around.toPx()
                with(CushionArt) { cushion(card.look, card.category, card.played, Offset(around, around), CushionWidth.toPx() / CushionArt.W) }
            }
            // What a screen reader is told: the whole name (the cushion may have room for part of it), the kind of game (which the
            // cushion says with a symbol only), and that it was played. The drawing itself says nothing: it is decoration.
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = card.title + ", " + card.kind + if (card.played) ", $played" else ""
            },
    ) {
        Text(
            card.title, color = card.look.type, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
            fontSize = if (long) 12.5.sp else 15.5.sp, lineHeight = if (long) 14.5.sp else 18.sp, letterSpacing = (-0.035).em,
            textAlign = TextAlign.Center, maxLines = if (long) 2 else 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = Around + CushionHeight * 0.56f).width(CushionWidth * 0.84f),
        )
    }
}

@Composable
private fun Pill(id: String, label: String, onClick: () -> Unit) {
    val tv = LocalTv.current
    val lit = tv?.isFocused(id) == true
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Tint)
            .tvTarget(id, label, onClick)                       // in focus it takes the red, like every button of the app
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp)
            .semantics { role = Role.Button; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (lit) Palette.Cream else Palette.Ink, maxLines = 1)
    }
}
