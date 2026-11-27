package app.couch.alhasapa.couches

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.data.Couches
import app.couch.alhasapa.data.Couple
import app.couch.alhasapa.tv.LocalTv
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.ui.theme.Palette

const val WELCOME_CREATE_TAG = "welcome-create"
const val WELCOME_JOIN_TAG = "welcome-join"
const val SETUP_COUCH_TAG = "setup-couch"
const val SETUP_A_TAG = "setup-a"
const val SETUP_B_TAG = "setup-b"
const val SETUP_START_TAG = "setup-start"

/**
 * The first screen of a new device (2.0): a couch of one's own, or the one that already is in this home. On a phone a new couch is
 * named here, and its two players; on a TV (which has no way to type but the remote) it begins unnamed, and the first phone that
 * joins names it. Joining: a phone chooses the home's TV on "my couches"; a TV waits for the home's phone to choose it ([tvName]
 * is how it is listed there).
 */
@Composable
fun WelcomeScreen(tv: Boolean, tvName: String, onCreate: (couch: String, a: String, b: String) -> Unit, onJoin: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val remote = LocalTv.current
    Column(
        Modifier.fillMaxSize().background(Palette.Cream).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        when (step) {
            0 -> {
                LaunchedEffect(Unit) { remote?.focus(WELCOME_CREATE_TAG) }
                Text("🛋️", fontSize = 56.sp)
                Text(
                    stringResource(R.string.welcome_title), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.welcome_sub), fontSize = 18.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
                WelcomeChoice(
                    stringResource(R.string.welcome_create), stringResource(if (tv) R.string.tv_welcome_create_sub else R.string.welcome_create_sub),
                    dark = true, id = WELCOME_CREATE_TAG,
                ) { if (tv) onCreate("", "", "") else step = 1 }
                WelcomeChoice(
                    stringResource(R.string.welcome_join), stringResource(if (tv) R.string.tv_welcome_join_sub else R.string.welcome_join_sub),
                    dark = false, id = WELCOME_JOIN_TAG,
                ) { if (tv) step = 2 else onJoin() }
            }
            1 -> SetupForm(onBack = { step = 0 }, onCreate = onCreate)
            else -> {
                LaunchedEffect(Unit) { remote?.focus("welcome-back") }
                Text(stringResource(R.string.tv_join_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
                for ((i, line) in listOf(stringResource(R.string.tv_join_1), stringResource(R.string.tv_join_2, tvName), stringResource(R.string.tv_join_3)).withIndex()) {
                    Row(Modifier.widthIn(max = 760.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, textAlign = TextAlign.Center,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Ink).padding(horizontal = 12.dp, vertical = 4.dp))
                        Text(line, fontSize = 22.sp, color = Palette.Ink)
                    }
                }
                WelcomeChoice(stringResource(R.string.tv_join_back), null, dark = false, id = "welcome-back") { step = 0 }
            }
        }
    }
}

@Composable
private fun WelcomeChoice(title: String, sub: String?, dark: Boolean, id: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(20.dp)).background(if (dark) Palette.Ink else Palette.Card)
            .border(BorderStroke(2.dp, Palette.Ink), RoundedCornerShape(20.dp)).tvTarget(id, title, onClick).testTag(id)
            .semantics(mergeDescendants = true) { role = Role.Button; contentDescription = if (sub != null) "$title. $sub" else title }.padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (dark) Palette.Cream else Palette.Ink)
        if (sub != null) Text(sub, fontSize = 16.sp, color = if (dark) Palette.Cream else Palette.Ink2)
    }
}

/** A new couch on a phone: who plays, and what the couch is called (after its two players, unless named otherwise). */
@Composable
private fun SetupForm(onBack: () -> Unit, onCreate: (couch: String, a: String, b: String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var couch by remember { mutableStateOf("") }
    Text(stringResource(R.string.setup_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.semantics { heading() })
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Ink, unfocusedBorderColor = Palette.Line2, cursorColor = Palette.Ink)
    val style = TextStyle(fontSize = 20.sp, color = Palette.Ink)
    OutlinedTextField(
        value = a, onValueChange = { a = it.take(Couple.MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.setup_player_a)) },
        textStyle = style, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), colors = colors, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag(SETUP_A_TAG),
    )
    OutlinedTextField(
        value = b, onValueChange = { b = it.take(Couple.MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.setup_player_b)) },
        textStyle = style, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), colors = colors, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag(SETUP_B_TAG),
    )
    OutlinedTextField(
        value = couch, onValueChange = { couch = it.take(Couches.MAX_NAME) }, singleLine = true, label = { Text(stringResource(R.string.setup_couch_name)) },
        supportingText = { Text(stringResource(R.string.setup_couch_auto, Couches.defaultName(a, b))) },
        textStyle = style, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), colors = colors, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag(SETUP_COUCH_TAG),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (a.isNotBlank() && b.isNotBlank()) SmallButton(stringResource(R.string.setup_start), dark = true, tag = SETUP_START_TAG) { onCreate(couch.trim(), a.trim(), b.trim()) }
        SmallButton(stringResource(R.string.couches_back), dark = false, onClick = onBack)
    }
}
