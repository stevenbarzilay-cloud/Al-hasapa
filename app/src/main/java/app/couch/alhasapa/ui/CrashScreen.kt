package app.couch.alhasapa.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.couch.alhasapa.R
import app.couch.alhasapa.tv.tvTarget
import app.couch.alhasapa.ui.theme.Palette

/** Shown on the start after a crash: what happened, in text that can be photographed, and one button to go on. */
@Composable
fun CrashScreen(report: String, onContinue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Cream).padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.crash_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, textAlign = TextAlign.Center)
            Text(stringResource(R.string.crash_body), fontSize = 20.sp, color = Palette.Ink2, textAlign = TextAlign.Center)
            Text(
                report.take(2_400), fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Palette.Ink,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Card)
                    .verticalScroll(rememberScrollState()).padding(12.dp),
            )
            val label = stringResource(R.string.crash_continue)
            Box(
                Modifier.heightIn(min = 56.dp).widthIn(min = 200.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Ink)
                    .tvTarget(onContinue).semantics { role = Role.Button; contentDescription = label },
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Cream, modifier = Modifier.padding(horizontal = 24.dp)) }
        }
    }
}
