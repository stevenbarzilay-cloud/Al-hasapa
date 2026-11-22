package app.couch.alhasapa.accounts

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import app.couch.alhasapa.tv.TvHost
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.couch.alhasapa.CouchApp
import app.couch.alhasapa.R
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.catalog.Site
import app.couch.alhasapa.data.CouchState
import app.couch.alhasapa.data.PieceStyle
import app.couch.alhasapa.data.PiecesConfig
import app.couch.alhasapa.player.ChromeTab
import app.couch.alhasapa.player.PlayerActivity
import app.couch.alhasapa.player.ProfileNames
import app.couch.alhasapa.player.WebProfiles
import app.couch.alhasapa.ui.PieceIcon
import app.couch.alhasapa.ui.theme.AlHasapaTheme
import app.couch.alhasapa.ui.theme.Palette

/** "החשבונות שלנו": log in once to the sites we pay for; the logins live only inside this app. */
class AccountsActivity : ComponentActivity() {
    private lateinit var tv: TvHost


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TvHost(this)
        tv.title = getString(app.couch.alhasapa.R.string.remote_screen_accounts)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val app = application as CouchApp
        val pieces = PiecesConfig.load(this)
        setContent {
            AlHasapaTheme {
                val state = app.repo.snapshot()
                val (nameA, nameB) = CouchState.names(state)
              tv.Layer {
                AccountsScreen(
                    sites = app.catalog.sites.values.filter { it.account },
                    nameA = nameA, nameB = nameB,
                    pieceA = pieces.styleFor(state, "a"), pieceB = pieces.styleFor(state, "b"),
                    onLogin = { site, title, profile -> startActivity(PlayerActivity.loginIntent(this, site.id, title, profile)) },
                    onChrome = { url -> ChromeTab.open(this, url) },
                    onBack = { finish() },
                )
              }
            }
        }
    }
}

/** One thing to log in to: a site, as one profile (LinkedIn has one per person). */
private data class Account(val site: Site, val title: String, val profile: String, val piece: PieceStyle?)

@Composable
fun AccountsScreen(
    sites: List<Site>,
    nameA: String,
    nameB: String,
    pieceA: PieceStyle,
    pieceB: PieceStyle,
    onLogin: (Site, String, String) -> Unit,
    onChrome: (String) -> Unit,
    onBack: () -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }   // back from a login: show the new status

    val accounts = sites.flatMap { s ->
        if (s.profile == "player") listOf(
            Account(s, "${s.label} · $nameA", ProfileNames.forPerson("a"), pieceA),
            Account(s, "${s.label} · $nameB", ProfileNames.forPerson("b"), pieceB),
        ) else listOf(Account(s, s.label, ProfileNames.GAMES, null))
    }

    Box(Modifier.fillMaxSize().background(Palette.Cream).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(
            Modifier.fillMaxWidth().widthIn(max = 720.dp).align(Alignment.TopCenter).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(stringResource(R.string.player_back), onBack)
            Text(stringResource(R.string.accounts_title), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.accounts_intro), fontSize = 17.sp, color = Palette.Ink2)
            Note(stringResource(R.string.accounts_google))
            if (!WebProfiles.supported()) Note(stringResource(R.string.accounts_no_profiles), warning = true)
            for (a in accounts) AccountCard(a, refresh, onLogin, onChrome)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AccountCard(a: Account, refresh: Int, onLogin: (Site, String, String) -> Unit, onChrome: (String) -> Unit) {
    // BEST EFFORT (see LoginDetector): the cookie jar of this profile, names only.
    val header = remember(refresh, a.profile) {
        runCatching { WebProfiles.cookies(if (WebProfiles.supported()) a.profile else WebProfiles.DEFAULT).getCookie("https://www.${a.site.domains.first()}") }.getOrNull()
    }
    val status = LoginDetector.status(header, a.site)
    var showCookies by remember { mutableStateOf(false) }
    val statusText = when (status) {
        LoginDetector.Status.LOGGED_IN -> stringResource(R.string.accounts_logged_in)
        LoginDetector.Status.LOGGED_OUT -> stringResource(R.string.accounts_logged_out)
        LoginDetector.Status.UNKNOWN -> stringResource(R.string.accounts_unknown)
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Card)
            .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            a.piece?.let { PieceIcon(it, 30.dp) }
            Text(a.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
            Text(
                statusText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = if (status == LoginDetector.Status.LOGGED_IN) Palette.Ink else Palette.Ink2,
                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (status == LoginDetector.Status.LOGGED_IN) Palette.Tint else ComposeColor.Transparent)
                    .border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(stringResource(R.string.accounts_login)) { onLogin(a.site, a.title, a.profile) }
            SecondaryButton(stringResource(R.string.player_open_chrome)) { onChrome(a.site.loginUrl ?: "https://${a.site.domains.first()}") }
        }
        TextButton(stringResource(if (showCookies) R.string.accounts_hide_cookies else R.string.accounts_show_cookies)) { showCookies = !showCookies }
        if (showCookies) {
            val names = LoginDetector.names(header)
            Text(
                if (names.isEmpty()) stringResource(R.string.accounts_no_cookies) else names.joinToString(", "),
                fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Palette.Ink2,
            )
        }
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(
        text, fontSize = 15.sp, color = Palette.Ink,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (warning) Palette.Pink.copy(alpha = 0.45f) else Palette.Tint).padding(14.dp),
    )
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 52.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Ink).tvTarget(onClick)
            .padding(horizontal = 22.dp).semantics { role = Role.Button; contentDescription = text },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Palette.Cream, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 52.dp).clip(RoundedCornerShape(12.dp)).border(BorderStroke(1.dp, Palette.Line2), RoundedCornerShape(12.dp))
            .tvTarget(onClick).padding(horizontal = 18.dp).semantics { role = Role.Button; contentDescription = text },
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Palette.Ink, fontSize = 17.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(10.dp)).tvTarget(onClick).padding(horizontal = 6.dp)
            .semantics { role = Role.Button; contentDescription = text },
        contentAlignment = Alignment.CenterStart,
    ) { Text(text, color = Palette.Ink2, fontSize = 16.sp, fontWeight = FontWeight.Medium) }
}
