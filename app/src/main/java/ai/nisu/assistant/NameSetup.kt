package ai.nisu.assistant

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v3.140: shown once, right after the first-run guide. The user can always set his own name (free or premium).
 * Changing the AI's name needs a premium key (a free user sees it locked).
 */
@Composable
internal fun NameSetupScreen(ctx: Context, onDone: () -> Unit, onKey: () -> Unit) {
    val premium by PremiumGate.unlocked.collectAsState()
    var own by remember { mutableStateOf(if (Personal.ownerIsCustom(ctx)) Personal.ownerName(ctx) else "") }
    var ai by remember { mutableStateOf(if (Personal.aiIsCustom(ctx)) Personal.aiName(ctx) else "") }
    val ownOk = Personal.clean(own) != null
    val aiOk = Personal.clean(ai) != null

    val save: () -> Unit = {
        val o = Personal.clean(own)
        if (o != null) Prefs.put(ctx, Prefs.NAME, o)
        val a = Personal.clean(ai)
        if (premium && a != null) {
            Prefs.put(ctx, Prefs.AI_NAME_VALUE, a)
            Prefs.put(ctx, Prefs.AI_NAME_MODE, "custom")
        }
        Prefs.putBool(ctx, Prefs.NAME_SETUP_DONE, true)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF020B1E), NisuUi.Bg, Color(0xFF01040C))))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            NisuLogo(72.dp)
            Spacer(Modifier.height(12.dp))
            Text("Set your name", fontSize = 28.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, color = NisuUi.Text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Tell her what to call you. You can change it later in Personalization.", fontSize = 13.sp, lineHeight = 20.sp, color = NisuUi.Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("YOUR NAME", fontSize = 11.sp, letterSpacing = 1.4.sp, color = NisuUi.Cyan, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = own,
                        onValueChange = { own = Personal.sanitize(it) },
                        placeholder = { Text("Your name", color = NisuUi.Dim) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NisuUi.Cyan, unfocusedBorderColor = NisuUi.Line,
                            focusedTextColor = NisuUi.Text, unfocusedTextColor = NisuUi.Text, cursorColor = NisuUi.Cyan
                        )
                    )
                    Text(own.length.toString() + "/" + Personal.MAX, fontSize = 11.sp, color = NisuUi.Muted)
                }
            }

            Spacer(Modifier.height(12.dp))

            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AI NAME", fontSize = 11.sp, letterSpacing = 1.4.sp, color = NisuUi.Cyan, fontWeight = FontWeight.Bold)
                    if (premium) {
                        OutlinedTextField(
                            value = ai,
                            onValueChange = { ai = Personal.sanitize(it) },
                            placeholder = { Text(Personal.DEFAULT_AI, color = NisuUi.Dim) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NisuUi.Cyan, unfocusedBorderColor = NisuUi.Line,
                                focusedTextColor = NisuUi.Text, unfocusedTextColor = NisuUi.Text, cursorColor = NisuUi.Cyan
                            )
                        )
                        Text("Leave empty to keep " + Personal.DEFAULT_AI, fontSize = 11.sp, color = NisuUi.Muted)
                    } else {
                        Text(Personal.DEFAULT_AI + "  \uD83D\uDD12", fontSize = 16.sp, color = NisuUi.Text)
                        Text("Changing the AI's name needs a Premium key.", fontSize = 12.sp, color = NisuUi.Amber)
                        MChip("ADD PREMIUM KEY", NisuUi.Amber) { save(); onKey() }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Brush.horizontalGradient(listOf(NisuUi.Cyan, NisuUi.Blue)))
                    .clickable { save(); onDone() },
                contentAlignment = Alignment.Center
            ) {
                Text(if (ownOk || (premium && aiOk)) "CONTINUE" else "CONTINUE WITHOUT NAME", fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, color = Color(0xFF020B1E))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Skip for now", fontSize = 13.sp, color = NisuUi.Muted,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .clickable { Prefs.putBool(ctx, Prefs.NAME_SETUP_DONE, true); onDone() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}
