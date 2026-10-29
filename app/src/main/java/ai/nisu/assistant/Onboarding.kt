package ai.nisu.assistant

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v2.43: first-run walkthrough (3 pages, Next / Skip), shown once on top of the home screen and reopenable from the side menu ("App Guide").
 *
 * It only teaches and points: the two buttons on page 2 finish the guide and open the real API Key / Permissions page, nothing is changed here.
 * Shown once per install ([Prefs.ONBOARDED]); Skip and Get Started both count as done.
 */
private class GuidePage(
    val accent: Color,
    val icon: Ic,
    val satellites: List<Ic>,
    val title: String,
    val subtitle: String,
)

private val GUIDE_PAGES = listOf(
    GuidePage(NisuUi.Cyan, Ic.WAVE, listOf(Ic.MIC, Ic.CHAT, Ic.HEART, Ic.BOLT),
        "Meet NISU AI", "Your voice, your world. A companion that lives in your phone and talks like a real person."),
    GuidePage(NisuUi.Violet, Ic.KEY, listOf(Ic.SHIELD, Ic.SLIDERS, Ic.CHECK),
        "Set up in two minutes", "Two quick things and she is ready to listen."),
    GuidePage(NisuUi.Green, Ic.MIC, listOf(Ic.WAVE, Ic.STAR, Ic.ROBOT, Ic.CHAT),
        "Talk to her", "No typing needed. Just speak, the way you would on a phone call."),
)

@Composable
internal fun OnboardingFlow(onFinish: () -> Unit, onAddKey: () -> Unit, onPermissions: () -> Unit) {
    val pager = rememberPagerState(pageCount = { GUIDE_PAGES.size })
    val scope = rememberCoroutineScope()
    val last = GUIDE_PAGES.size - 1

    BackHandler(enabled = pager.currentPage > 0) { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF020B1E), NisuUi.Bg, Color(0xFF01040C))))
            // the screen is opaque and swallows touches, so nothing underneath can be pressed while the guide is open
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // top bar: small wordmark on the left, Skip on the right (hidden on the last page, but it keeps its height)
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NISU AI", fontSize = 13.sp, letterSpacing = 3.sp, color = NisuUi.Muted, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                if (pager.currentPage < last) {
                    Text(
                        "Skip", fontSize = 15.sp, color = NisuUi.Muted, letterSpacing = 0.6.sp,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .clickable { onFinish() }.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                // parallax: the picture drifts and fades a little faster than the text while the page is being swiped
                val shift = (pager.currentPage - index) + pager.currentPageOffsetFraction
                val p = GUIDE_PAGES[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.padding(top = 6.dp).graphicsLayer {
                            translationX = shift * size.width * 0.45f
                            alpha = 1f - abs(shift).coerceIn(0f, 1f)
                        }
                    ) { HeroOrb(p, index == 0) }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        p.title, fontSize = 30.sp, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                        color = NisuUi.Text, textAlign = TextAlign.Center, letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        p.subtitle, fontSize = 15.sp, lineHeight = 22.sp, color = NisuUi.Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                    Spacer(Modifier.height(22.dp))

                    when (index) {
                        0 -> {
                            GuideRow(Ic.CHAT, "Talks naturally", "In any language you speak: Bengali, Hindi, English, Arabic, Spanish and more, with real feeling in her voice.", p.accent)
                            GuideRow(Ic.SCREEN, "Uses your phone for you", "Opens apps, makes calls, reads the screen and changes settings.", p.accent)
                            GuideRow(Ic.HEART, "Handles your messages", "Reads WhatsApp and Messenger and drafts replies. You decide what is sent.", p.accent)
                        }
                        1 -> {
                            GuideRow(Ic.KEY, "1. Add your Gemini API key", "A free key from Google AI Studio is enough. She needs it to think and speak.", p.accent,
                                action = "ADD KEY", onAction = onAddKey)
                            GuideRow(Ic.SHIELD, "2. Allow permissions", "Microphone first, then Phone control (Accessibility) and Notification access.", p.accent,
                                action = "PERMISSIONS", onAction = onPermissions)
                            Tip("Android 13+: if a switch is greyed out, open App info, tap the ⋮ menu and choose \"Allow restricted settings\".")
                        }
                        else -> {
                            GuideRow(Ic.MIC, "Tap the mic to start", "The big mic button on the home screen starts her. Tap it again to mute.", p.accent)
                            GuideRow(Ic.BOLT, "Wake and sleep by voice", "Say \"Hey Nishu\" to wake her and \"Sleep Nishu\" to let her rest.", p.accent)
                            GuideRow(Ic.HEART, "Pick her style", "Care, Boss or Friend. Change it any time in Preferences.", p.accent)
                            Tip("Everything else is in the side menu. You can open this guide again from there.")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // bottom: dots + main button
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 6.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GUIDE_PAGES.forEachIndexed { i, g ->
                        val on = i == pager.currentPage
                        val w by animateDpAsState(if (on) 26.dp else 8.dp, tween(260), label = "dotW")
                        val c by animateColorAsState(if (on) g.accent else NisuUi.Dim, tween(260), label = "dotC")
                        Box(Modifier.height(8.dp).width(w).clip(CircleShape).background(c))
                    }
                }
                Spacer(Modifier.height(18.dp))
                val accent = GUIDE_PAGES[pager.currentPage].accent
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(accent, NisuUi.Blue)))
                        .clickable {
                            if (pager.currentPage < last) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } else onFinish()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (pager.currentPage < last) "Next" else "Get Started",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = Color(0xFF020B1E)
                    )
                }
            }
        }
    }
}

/** Glowing orb with slowly turning rings and small icons orbiting it. The first page shows the NISU AI logo in the middle. */
@Composable
private fun HeroOrb(p: GuidePage, logo: Boolean) {
    val spin = rememberInfiniteTransition(label = "orb")
    val a by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(28000, easing = LinearEasing), RepeatMode.Restart), label = "a")
    val pulse by spin.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "pulse")
    val orbitPx = with(LocalDensity.current) { 104.dp.toPx() }

    Box(Modifier.size(250.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(p.accent.copy(alpha = 0.30f), p.accent.copy(alpha = 0.06f), Color.Transparent), c, r), r * pulse, c)
            drawCircle(p.accent.copy(alpha = 0.22f), r * 0.84f, c, style = Stroke(1.dp.toPx()))
            rotate(a, c) {
                drawCircle(p.accent.copy(alpha = 0.55f), r * 0.62f, c,
                    style = Stroke(1.4.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 14.dp.toPx()))))
            }
            rotate(-a * 0.6f, c) {
                drawCircle(NisuUi.Blue.copy(alpha = 0.35f), r * 0.74f, c,
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 16.dp.toPx()))))
            }
        }
        // centre badge
        Box(
            Modifier.size(104.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(NisuUi.CardHi, NisuUi.Card)))
                .border(1.5.dp, Brush.linearGradient(listOf(p.accent, NisuUi.Blue, p.accent.copy(alpha = 0.4f))), CircleShape),
            contentAlignment = Alignment.Center
        ) { if (logo) NisuLogo(92.dp) else MIcon(p.icon, p.accent, 44.dp) }

        // satellites
        p.satellites.forEachIndexed { i, ic ->
            val rad = Math.toRadians((a + i * 360.0 / p.satellites.size))
            Box(
                Modifier.size(40.dp)
                    .graphicsLayer { translationX = (cos(rad) * orbitPx).toFloat(); translationY = (sin(rad) * orbitPx).toFloat() }
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(NisuUi.CardHi, NisuUi.Card)))
                    .border(1.dp, p.accent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) { MIcon(ic, p.accent, 20.dp) }
        }
    }
}

/** One explanation card: icon, title, short text, and an optional action chip on the right. */
@Composable
private fun GuideRow(icon: Ic, title: String, body: String, accent: Color, action: String? = null, onAction: () -> Unit = {}) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).clip(shape)
            .background(Brush.verticalGradient(listOf(NisuUi.CardHi.copy(alpha = 0.85f), NisuUi.Card.copy(alpha = 0.85f))))
            .border(1.dp, NisuUi.Line, shape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            MIcon(icon, accent, 22.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = NisuUi.Text)
            Spacer(Modifier.height(3.dp))
            Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = NisuUi.Muted)
            if (action != null) {
                Spacer(Modifier.height(10.dp))
                MChip(action, accent) { onAction() }
            }
        }
    }
}

/** A quiet hint under the cards. */
@Composable
private fun Tip(text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(NisuUi.Amber))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.5.sp, lineHeight = 18.sp, color = NisuUi.Muted)
    }
}
