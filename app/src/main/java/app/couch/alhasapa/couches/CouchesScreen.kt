package app.couch.alhasapa.couches

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.couch.alhasapa.R
import app.couch.alhasapa.data.CouchRef
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.data.Person
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.remote.GuestEntry
import app.couch.alhasapa.remote.KeyClient
import app.couch.alhasapa.remote.SavedCouch
import app.couch.alhasapa.remote.Visit
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.Palette

/** A TV found on this network: where it is, and who it said it is (its couch, when it has one). */
data class Nearby(val address: String, val tv: KeyClient.Tv)

/** What "my couches" shows: the home, the friends' couches, the visit, the TVs around, and what the phone is connected to now. */
data class CouchesView(
    val home: CouchRef?,
    val homeTvs: Int,
    val saved: List<SavedCouch>,
    val visit: Visit?,
    /** The TV the phone is connected to this moment, and the couch it is of; null when it is connected to none. */
    val connected: KeyClient.Tv? = null,
    val looking: Boolean = false,
    val nearby: List<Nearby> = emptyList(),
    val scanning: Boolean = false,
    /** Results played alone for friends' couches that their TVs do not keep yet, with the games' names. */
    val pending: List<Pair<GuestEntry, String>> = emptyList(),
    val link: AppLink.Ready? = null,
    /** Opened from the first screen of a new phone: only the TVs to join are shown. */
    val joining: Boolean = false,
)

interface CouchesActions {
    fun back()
    fun rename()
    fun endVisit()
    fun keyboard()
    fun sendPending()
    fun startVisit(c: SavedCouch)
    fun setHome(c: SavedCouch)
    fun remove(c: SavedCouch)
    fun pick(n: Nearby)
    fun scan()
    fun typeAddress()
    fun shareApp()
    fun checkLink()
}

const val COUCH_HOME_TAG = "couch-home"
const val COUCH_VISIT_TAG = "couch-visit"
const val COUCH_END_VISIT_TAG = "couch-end-visit"
const val COUCH_CONNECTED_TAG = "couch-connected"
const val COUCH_SHARE_TAG = "couch-share-app"
const val COUCH_CODE_TAG = "couch-code"
const val COUCH_NAME_TAG = "couch-name"

/**
 * "הספות שלי" (2.0): which couch is the phone's home, which friends' couches it keeps for the next visits, where it is a guest this
 * evening, and which TV it is connected to this moment. Each of these is said in words and with a mark of its own, never by colour
 * alone; and a TV that was merely found on the network is listed apart, as something to choose, not as something connected.
 */
@Composable
fun CouchesScreen(v: CouchesView, a: CouchesActions) {
    Column(
        Modifier.fillMaxSize().background(Palette.Cream).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallButton(stringResource(R.string.couches_back), dark = false, onClick = a::back)
            Text(
                stringResource(if (v.joining) R.string.join_title else R.string.couches_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (v.joining) Text(stringResource(R.string.join_hint), fontSize = 17.sp, color = Palette.Ink2)

        // What the phone is connected to this moment: one line, apart from everything it merely knows.
        if (!v.joining) {
            val line = when {
                v.connected != null -> stringResource(R.string.couch_connected_tv, v.connected.name) + (v.connected.couch?.let { " · " + it.name } ?: "")
                v.looking -> stringResource(R.string.couch_looking)
                else -> stringResource(R.string.couch_not_connected)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (v.connected != null) Palette.Ink else Palette.Tint).padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag(COUCH_CONNECTED_TAG).semantics(mergeDescendants = true) { contentDescription = line },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(if (v.connected != null) "🔗" else "○", fontSize = 18.sp, color = if (v.connected != null) Palette.Cream else Palette.Ink2)
                Text(line, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = if (v.connected != null) Palette.Cream else Palette.Ink)
            }
        }

        // The visit: where the phone's holder is a guest this evening, and the way out of it.
        v.visit?.takeIf { !v.joining }?.let { visit ->
            val here = v.connected?.couch?.id == visit.couch
            Card(Modifier.testTag(COUCH_VISIT_TAG)) {
                Text("🛋️ " + stringResource(R.string.visit_bar, Couches.inCouch(visit.name)), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Text(
                    stringResource(R.string.visit_as, visit.meName) + " · " + stringResource(if (here) R.string.visit_connected else R.string.visit_lost),
                    fontSize = 16.sp, color = Palette.Ink2,
                )
                for ((e, game) in v.pending.filter { it.first.couch == visit.couch }) {
                    Text("⏳ " + stringResource(R.string.visit_pending, game, Couches.toCouch(e.couchName)), fontSize = 16.sp, color = Palette.Ink)
                }
                FlowButtons {
                    SmallButton(stringResource(R.string.visit_end), dark = true, tag = COUCH_END_VISIT_TAG, onClick = a::endVisit)
                    SmallButton(stringResource(R.string.visit_keyboard), dark = false, onClick = a::keyboard)
                    if (v.pending.any { it.first.couch == visit.couch }) SmallButton(stringResource(R.string.solo_send_now), dark = false, onClick = a::sendPending)
                }
            }
        }

        // The home couch.
        if (!v.joining) Card(Modifier.testTag(COUCH_HOME_TAG)) {
            Badge(stringResource(R.string.couch_home_badge))
            Text(v.home?.name.orEmpty(), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            v.home?.let { Text(stringResource(R.string.couch_id, Couches.short(it.id)), fontSize = 14.sp, color = Palette.Ink2) }
            Text(
                if (v.homeTvs == 0) stringResource(R.string.couch_home_no_tv) else stringResource(R.string.couch_home_tvs, v.homeTvs),
                fontSize = 16.sp, color = Palette.Ink2,
            )
            FlowButtons { SmallButton(stringResource(R.string.couch_rename), dark = false, onClick = a::rename) }
        }

        // Friends' couches kept for the next visits.
        if (!v.joining) {
            SectionTitle(stringResource(R.string.couch_saved_section))
            if (v.saved.isEmpty()) Text(stringResource(R.string.couch_saved_none), fontSize = 16.sp, color = Palette.Ink2)
            for (c in v.saved) Card {
                Badge(stringResource(R.string.couch_saved_badge))
                Text(c.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                Text(
                    stringResource(R.string.couch_id, Couches.short(c.id)) + (c.meName?.let { " · " + stringResource(R.string.couch_saved_me, it) } ?: ""),
                    fontSize = 14.sp, color = Palette.Ink2,
                )
                if (c.archived) Text(stringResource(R.string.couch_was_home), fontSize = 14.sp, color = Palette.Ink2)
                for ((e, game) in v.pending.filter { it.first.couch == c.id && v.visit?.couch != c.id }) {
                    Text("⏳ " + stringResource(R.string.visit_pending, game, Couches.toCouch(e.couchName)), fontSize = 16.sp, color = Palette.Ink)
                }
                FlowButtons {
                    if (v.visit?.couch != c.id) SmallButton(stringResource(R.string.couch_visit_start), dark = true, onClick = { a.startVisit(c) })
                    SmallButton(stringResource(R.string.couch_set_home), dark = false, onClick = { a.setHome(c) })
                    SmallButton(stringResource(R.string.couch_remove), dark = false, onClick = { a.remove(c) })
                }
            }
        }

        // The TVs on this network: found, not connected. Each is chosen by hand.
        SectionTitle(stringResource(R.string.couch_found_section))
        if (!v.joining) Text(stringResource(R.string.couch_found_hint), fontSize = 15.sp, color = Palette.Ink2)
        if (v.nearby.isEmpty()) Text(stringResource(if (v.scanning) R.string.couch_found_scan else R.string.couch_found_none), fontSize = 16.sp, color = Palette.Ink2)
        for (n in v.nearby) {
            val couch = n.tv.couch
            val what = when {
                couch == null -> stringResource(R.string.couch_found_new)
                couch.id == v.home?.id -> stringResource(R.string.couch_found_is_home)
                else -> couch.name
            }
            val label = stringResource(R.string.couch_found_tv, n.tv.name) + " · " + what
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Card)
                    .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp)).clickable { a.pick(n) }
                    .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = label }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(what, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                    Text(stringResource(R.string.couch_found_tv, n.tv.name), fontSize = 15.sp, color = Palette.Ink2)
                }
                Text("‹", fontSize = 24.sp, color = Palette.Ink2)
            }
        }
        FlowButtons {
            SmallButton(stringResource(R.string.couch_found_again), dark = false, onClick = a::scan)
            SmallButton(stringResource(R.string.couch_typed_address), dark = false, onClick = a::typeAddress)
        }

        // The app for a friend: one link, the same for everyone, offered once both downloads are there.
        if (!v.joining) {
            SectionTitle(stringResource(R.string.share_app))
            Card(Modifier.testTag(COUCH_SHARE_TAG)) {
                Text(stringResource(R.string.share_app_sub), fontSize = 16.sp, color = Palette.Ink2)
                val status = when (v.link) {
                    null -> stringResource(R.string.share_app_checking)
                    AppLink.Ready.YES -> stringResource(R.string.share_app_ready)
                    AppLink.Ready.NO -> stringResource(R.string.share_app_not_ready)
                    AppLink.Ready.UNKNOWN -> stringResource(R.string.share_app_offline)
                }
                Text(status, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                FlowButtons {
                    if (v.link == AppLink.Ready.YES) SmallButton(stringResource(R.string.share_app_send), dark = true, onClick = a::shareApp)
                    else if (v.link != null) SmallButton(stringResource(R.string.share_app_again), dark = false, onClick = a::checkLink)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
}

@Composable
private fun Badge(text: String) {
    Text(
        text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.Ink,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Tint).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Card).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowButtons(content: @Composable () -> Unit) {
    FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
internal fun SmallButton(label: String, dark: Boolean, tag: String? = null, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (dark) Palette.Ink else Palette.Tint)
            .border(BorderStroke(1.dp, if (dark) Palette.Ink else Palette.Line2), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick).then(if (tag != null) Modifier.testTag(tag) else Modifier).semantics { role = Role.Button }.padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (dark) Palette.Cream else Palette.Ink, textAlign = TextAlign.Center) }
}

@Composable
internal fun DialogCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(22.dp)).background(Palette.Cream)
                .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

/** A choice with a line under it saying what it means, wide enough to be read and pressed. */
@Composable
internal fun Choice(title: String, sub: String?, dark: Boolean, tag: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(if (dark) Palette.Ink else Palette.Card)
            .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(16.dp)).clickable(onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = if (sub != null) "$title. $sub" else title }.padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val ink: Color = if (dark) Palette.Cream else Palette.Ink
        Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ink)
        if (sub != null) Text(sub, fontSize = 14.sp, color = ink)
    }
}

/**
 * A friend's TV was chosen: its couch by name, and the two ways to be a guest there (for this evening, or kept for the next visits).
 * Neither changes the home couch; making it the home is a separate, smaller choice with its own confirmation.
 */
@Composable
fun VisitOptionsDialog(couch: CouchRef, tvName: String, onEvening: () -> Unit, onKeep: () -> Unit, onHome: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        Text(couch.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(stringResource(R.string.couch_tv_of, tvName), fontSize = 16.sp, color = Palette.Ink2)
        Choice(stringResource(R.string.couch_visit_evening), stringResource(R.string.couch_visit_evening_sub), dark = true, tag = "visit-evening", onClick = onEvening)
        Choice(stringResource(R.string.couch_visit_keep), stringResource(R.string.couch_visit_keep_sub), dark = false, tag = "visit-keep", onClick = onKeep)
        Text(stringResource(R.string.couch_visit_note), fontSize = 14.sp, color = Palette.Ink2)
        FlowButtons {
            SmallButton(stringResource(R.string.couch_set_home), dark = false, onClick = onHome)
            SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
        }
    }
}

/** A TV with no couch yet (or the home's own TV) was chosen: it joins the home couch, with what the home keeps. */
@Composable
fun ConnectHomeDialog(home: CouchRef, tvName: String, onConnect: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        Text(stringResource(R.string.couch_tv_of, tvName), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(stringResource(R.string.couch_connect_home_body, home.name), fontSize = 17.sp, color = Palette.Ink)
        Choice(stringResource(R.string.couch_connect_home), null, dark = true, tag = "connect-home", onClick = onConnect)
        SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
    }
}

/** A new phone joining the couch of the TV it chose. */
@Composable
fun JoinDialog(couch: CouchRef?, tvName: String, onJoin: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        Text(couch?.name ?: stringResource(R.string.couch_found_new), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(stringResource(R.string.couch_tv_of, tvName), fontSize = 16.sp, color = Palette.Ink2)
        if (couch == null) Text(stringResource(R.string.couch_fresh_join), fontSize = 17.sp, color = Palette.Ink)
        else {
            Text(stringResource(R.string.couch_set_home_code), fontSize = 17.sp, color = Palette.Ink)
            Choice(stringResource(R.string.couch_join_home), null, dark = true, tag = "join-home", onClick = onJoin)
        }
        SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
    }
}

/**
 * Another couch is to be the home: said in so many words, with the new home's name, what it means for the games played alone from
 * now on, and that nothing played until now moves.
 */
@Composable
fun SetHomeDialog(to: CouchRef, was: CouchRef?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        Text(stringResource(R.string.couch_set_home_title, to.name), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(stringResource(R.string.couch_set_home_body, was?.let { Couches.inCouch(it.name) } ?: ""), fontSize = 17.sp, color = Palette.Ink)
        Text(stringResource(R.string.couch_set_home_code), fontSize = 15.sp, color = Palette.Ink2)
        Choice(stringResource(R.string.couch_set_home_yes), null, dark = true, tag = "set-home-yes", onClick = onConfirm)
        SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
    }
}

@Composable
fun RemoveCouchDialog(c: SavedCouch, pending: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogCard(onDismiss) {
        Text(stringResource(R.string.couch_remove_title, c.name), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(stringResource(R.string.couch_remove_body), fontSize = 17.sp, color = Palette.Ink)
        if (pending) Text(stringResource(R.string.couch_remove_pending), fontSize = 16.sp, color = Palette.Ink)
        if (c.archived) Text(stringResource(R.string.couch_remove_archived), fontSize = 16.sp, color = Palette.Ink)
        FlowButtons {
            SmallButton(stringResource(R.string.couch_remove_yes), dark = true, tag = "remove-yes", onClick = onConfirm)
            SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
        }
    }
}

/** A few words typed in: a couch's name, a TV's address, the four digits the TV shows. */
@Composable
fun TextDialog(
    title: String, initial: String, hint: String?, ok: String, digits: Boolean = false, max: Int = Couches.MAX_NAME, note: String? = null,
    tag: String = COUCH_NAME_TAG, onOk: (String) -> Unit, onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    DialogCard(onDismiss) {
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        if (note != null) Text(note, fontSize = 16.sp, color = Palette.Ink2)
        OutlinedTextField(
            value = text, onValueChange = { text = it.take(max) }, singleLine = true,
            placeholder = hint?.let { h -> { Text(h, color = Palette.Line2) } },
            textStyle = TextStyle(fontSize = 22.sp, color = Palette.Ink),
            keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag(tag),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line2, cursorColor = Palette.Ink),
        )
        FlowButtons {
            SmallButton(ok, dark = true, tag = "$tag-ok", onClick = { onOk(text.trim()) })
            SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
        }
    }
}

/**
 * "מי משחק מהטלפון הזה?" at a friend's couch: one of that couch's own players, as its TV names them, or someone new there (a guest of
 * the evening; someone who comes often when the couch is kept for the next visits). Who is chosen here is the phone's holder at this
 * couch only: a player of the same name at home is another player.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuestWhoDialog(couch: CouchRef, people: List<Person>, me: String?, pieces: PiecesConfig, onPick: (Person) -> Unit, onNew: (String) -> Unit, onDismiss: () -> Unit) {
    var who by remember { mutableStateOf(me?.takeIf { w -> people.any { it.who == w } }) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    DialogCard(onDismiss) {
        Text(stringResource(R.string.couch_who_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        Text(couch.name, fontSize = 16.sp, color = Palette.Ink2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in people) {
                val chosen = !adding && p.who == who
                Row(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (chosen) Palette.Ink else Palette.Tint)
                        .clickable { who = p.who; adding = false }.semantics { role = Role.RadioButton; selected = chosen; contentDescription = p.name }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PieceIcon(pieces.styleOf(p), 26.dp, label = "")
                    Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (chosen) Palette.Cream else Palette.Ink, maxLines = 1)
                }
            }
            val newLabel = stringResource(R.string.couch_who_new)
            Box(
                Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (adding) Palette.Ink else Palette.Card)
                    .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(14.dp)).clickable { adding = true; who = null }
                    .semantics { role = Role.RadioButton; selected = adding; contentDescription = newLabel }.testTag("who-new").padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(newLabel, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (adding) Palette.Cream else Palette.Ink) }
        }
        if (adding) OutlinedTextField(
            value = name, onValueChange = { name = it.take(Couple.MAX_NAME) }, singleLine = true,
            label = { Text(stringResource(R.string.couch_who_name)) },
            textStyle = TextStyle(fontSize = 18.sp, color = Palette.Ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("who-name"),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line2, cursorColor = Palette.Ink),
        )
        val picked = people.firstOrNull { it.who == who }
        val ready = if (adding) name.isNotBlank() else picked != null
        FlowButtons {
            if (ready) SmallButton(stringResource(R.string.couch_who_ok), dark = true, tag = "who-ok", onClick = { if (adding) onNew(name.trim()) else picked?.let(onPick) })
            SmallButton(stringResource(R.string.couch_cancel), dark = false, onClick = onDismiss)
        }
    }
}
