package ai.nisu.assistant

import android.content.Context
import android.content.Intent
import androidx.compose.ui.text.withStyle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Colours of the NISU AI look: near-black background, deep-blue glass cards, cyan / blue / violet accents. */
object NisuUi {
    val Bg = Color(0xFF010713)          // deep midnight navy
    val Card = Color(0xFF071228)
    val CardHi = Color(0xFF0B1B3D)
    val Line = Color(0xFF16336E)        // blue hairline
    val Cyan = Color(0xFF38B6FF)        // neon blue (main accent)
    val Blue = Color(0xFF2F6BFF)        // electric blue
    val Violet = Color(0xFF8A6CFF)      // soft violet
    val Text = Color(0xFFEAF2FF)        // cool white
    val Muted = Color(0xFF8DA2C8)
    val Dim = Color(0xFF4A5B80)
    val Green = Color(0xFF63D6A0)
    val Amber = Color(0xFFF0BE6A)
    val Red = Color(0xFFEA6F66)
    val Gold = Cyan
    val GoldBrush = Brush.linearGradient(listOf(Color(0xFF7FD6FF), Color(0xFF2F8BFF), Color(0xFF2F4BFF)))
}

@Composable
fun NisuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NisuUi.Cyan, secondary = NisuUi.Violet, background = NisuUi.Bg, surface = NisuUi.Card,
            surfaceVariant = NisuUi.CardHi, outline = NisuUi.Line,
            onPrimary = Color.Black, onBackground = NisuUi.Text, onSurface = NisuUi.Text
        )
    ) { Surface(Modifier.fillMaxSize(), color = NisuUi.Bg, content = content) }
}

fun emoColor(e: String) = when (e.lowercase()) {
    "happy" -> Color(0xFFFFB300)
    "excited" -> Color(0xFFFF4D8D)
    "sad" -> Color(0xFF4A90E2)
    "love" -> Color(0xFFFF6FB5)
    "surprised" -> Color(0xFF2EE6C5)
    "angry" -> Color(0xFFFF5252)
    "jealous" -> Color(0xFF9C6BFF)
    "sulky" -> Color(0xFFFF8A65)
    "playful" -> Color(0xFFFFD54F)
    "worried" -> Color(0xFF7FB3D5)
    "shy" -> Color(0xFFFF9EC4)
    "sleepy" -> Color(0xFF8E9AAF)
    "proud" -> Color(0xFFFFC857)
    "curious" -> Color(0xFF5ED3F3)
    "bored" -> Color(0xFF9AA5B1)
    "nervous" -> Color(0xFFB5A3FF)
    "disappointed" -> Color(0xFF6C8EBF)
    "grateful" -> Color(0xFFFF9FD0)
    "lonely" -> Color(0xFF5C7BA8)
    "confused" -> Color(0xFFC3B1E1)
    "scared" -> Color(0xFF9FD8D0)
    "annoyed" -> Color(0xFFFF7043)
    "flirty" -> Color(0xFFFF7EB6)
    "guilty" -> Color(0xFFA1887F)
    "relieved" -> Color(0xFF80CBC4)
    "hopeful" -> Color(0xFFFFE082)
    "mischievous" -> Color(0xFFFF9F43)
    "overwhelmed" -> Color(0xFFB39DDB)
    "nostalgic" -> Color(0xFFD7A86E)
    "heartbroken" -> Color(0xFF5C6BC0)
    else -> NisuUi.Cyan
}

/** Switch the running session to another Live model (or remember it for the next start). Same behaviour as before. */
object ModelPick {
    fun apply(ctx: Context, id: String) {
        Prefs.put(ctx, Prefs.MODEL, id)
        if (AppState.running.value)
            ctx.startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_SWITCH).putExtra("model", id))
    }
}

fun statusLabel(running: Boolean, online: Boolean, switching: Boolean, mode: AssistantMode = AssistantMode.ACTIVE): String = when {
    running && mode == AssistantMode.SLEEP -> "Sleeping"
    switching -> "Switching model"
    online -> "Online"
    running -> "Connecting..."
    else -> "Offline"
}

// ------------------------------------------------------------------------------------------------ icons (drawn, no icon library needed)

enum class Ic { MENU, BACK, CHEVRON, BOLT, SHIELD, WAVE, KEY, CHAT, MIC, SCREEN, ROBOT, CHECK, HEART, SLIDERS, STAR, BRAIN, SWAP }

@Composable
fun MIcon(kind: Ic, tint: Color = NisuUi.Cyan, dp: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(dp)) {
        val s = this.size.minDimension
        val st = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun poly(close: Boolean, vararg pts: Pair<Float, Float>) {
            val path = Path()
            pts.forEachIndexed { i, pt -> if (i == 0) path.moveTo(pt.first * s, pt.second * s) else path.lineTo(pt.first * s, pt.second * s) }
            if (close) path.close()
            drawPath(path, tint, style = st)
        }
        when (kind) {
            Ic.MENU -> {
                poly(false, 0.2f to 0.28f, 0.8f to 0.28f)
                poly(false, 0.2f to 0.5f, 0.8f to 0.5f)
                poly(false, 0.2f to 0.72f, 0.8f to 0.72f)
            }
            Ic.BACK -> poly(false, 0.62f to 0.2f, 0.36f to 0.5f, 0.62f to 0.8f)
            Ic.CHEVRON -> poly(false, 0.38f to 0.2f, 0.64f to 0.5f, 0.38f to 0.8f)
            Ic.CHECK -> poly(false, 0.2f to 0.52f, 0.42f to 0.72f, 0.8f to 0.3f)
            Ic.BOLT -> poly(true, 0.58f to 0.06f, 0.22f to 0.56f, 0.46f to 0.56f, 0.40f to 0.94f, 0.78f to 0.40f, 0.54f to 0.40f)
            Ic.SHIELD -> {
                val p = Path()
                p.moveTo(0.5f * s, 0.08f * s)
                p.lineTo(0.85f * s, 0.2f * s)
                p.lineTo(0.85f * s, 0.5f * s)
                p.cubicTo(0.85f * s, 0.75f * s, 0.68f * s, 0.88f * s, 0.5f * s, 0.94f * s)
                p.cubicTo(0.32f * s, 0.88f * s, 0.15f * s, 0.75f * s, 0.15f * s, 0.5f * s)
                p.lineTo(0.15f * s, 0.2f * s)
                p.close()
                drawPath(p, tint, style = st)
                poly(false, 0.34f to 0.5f, 0.46f to 0.62f, 0.68f to 0.38f)
            }
            Ic.WAVE -> {
                val hs = floatArrayOf(0.2f, 0.45f, 0.7f, 1f, 0.6f, 0.4f, 0.2f)
                for (i in hs.indices) {
                    val x = (0.14f + i * 0.12f) * s
                    val h = hs[i] * 0.42f * s
                    drawLine(tint, Offset(x, s * 0.5f - h), Offset(x, s * 0.5f + h), strokeWidth = s * 0.075f, cap = StrokeCap.Round)
                }
            }
            Ic.KEY -> {
                drawCircle(tint, radius = s * 0.19f, center = Offset(s * 0.31f, s * 0.31f), style = st)
                poly(false, 0.44f to 0.44f, 0.86f to 0.86f)
                poly(false, 0.66f to 0.66f, 0.77f to 0.55f)
                poly(false, 0.76f to 0.76f, 0.87f to 0.65f)
            }
            Ic.CHAT -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.1f, s * 0.12f), size = Size(s * 0.8f, s * 0.62f), cornerRadius = CornerRadius(s * 0.2f), style = st)
                poly(false, 0.3f to 0.72f, 0.3f to 0.9f, 0.5f to 0.74f)
                for (x in floatArrayOf(0.34f, 0.5f, 0.66f)) drawCircle(tint, radius = s * 0.045f, center = Offset(s * x, s * 0.43f))
            }
            Ic.MIC -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.36f, s * 0.08f), size = Size(s * 0.28f, s * 0.5f), cornerRadius = CornerRadius(s * 0.14f), style = st)
                drawArc(tint, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = Offset(s * 0.24f, s * 0.26f), size = Size(s * 0.52f, s * 0.46f), style = st)
                poly(false, 0.5f to 0.72f, 0.5f to 0.9f)
                poly(false, 0.36f to 0.9f, 0.64f to 0.9f)
            }
            Ic.SCREEN -> {
                poly(false, 0.12f to 0.36f, 0.12f to 0.12f, 0.36f to 0.12f)
                poly(false, 0.64f to 0.12f, 0.88f to 0.12f, 0.88f to 0.36f)
                poly(false, 0.12f to 0.64f, 0.12f to 0.88f, 0.36f to 0.88f)
                poly(false, 0.64f to 0.88f, 0.88f to 0.88f, 0.88f to 0.64f)
            }
            Ic.ROBOT -> {
                drawCircle(tint, radius = s * 0.34f, center = Offset(s * 0.5f, s * 0.5f))
                drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.1f, s * 0.5f))
                drawCircle(tint, radius = s * 0.07f, center = Offset(s * 0.9f, s * 0.5f))
                drawCircle(NisuUi.Bg, radius = s * 0.055f, center = Offset(s * 0.38f, s * 0.45f))
                drawCircle(NisuUi.Bg, radius = s * 0.055f, center = Offset(s * 0.62f, s * 0.45f))
                drawArc(NisuUi.Bg, startAngle = 20f, sweepAngle = 140f, useCenter = false, topLeft = Offset(s * 0.36f, s * 0.46f), size = Size(s * 0.28f, s * 0.2f),
                    style = Stroke(width = s * 0.05f, cap = StrokeCap.Round))
            }
            Ic.HEART -> {
                val p = Path()
                p.moveTo(0.5f * s, 0.86f * s)
                p.cubicTo(0.04f * s, 0.54f * s, 0.14f * s, 0.12f * s, 0.5f * s, 0.34f * s)
                p.cubicTo(0.86f * s, 0.12f * s, 0.96f * s, 0.54f * s, 0.5f * s, 0.86f * s)
                drawPath(p, tint, style = st)
            }
            Ic.SLIDERS -> {
                poly(false, 0.14f to 0.25f, 0.86f to 0.25f)
                poly(false, 0.14f to 0.5f, 0.86f to 0.5f)
                poly(false, 0.14f to 0.75f, 0.86f to 0.75f)
                drawCircle(tint, radius = s * 0.09f, center = Offset(s * 0.68f, s * 0.25f))
                drawCircle(tint, radius = s * 0.09f, center = Offset(s * 0.34f, s * 0.5f))
                drawCircle(tint, radius = s * 0.09f, center = Offset(s * 0.6f, s * 0.75f))
            }
            Ic.BRAIN -> {
                drawCircle(tint, radius = s * 0.2f, center = Offset(s * 0.35f, s * 0.36f), style = st)
                drawCircle(tint, radius = s * 0.2f, center = Offset(s * 0.65f, s * 0.36f), style = st)
                drawCircle(tint, radius = s * 0.19f, center = Offset(s * 0.37f, s * 0.64f), style = st)
                drawCircle(tint, radius = s * 0.19f, center = Offset(s * 0.63f, s * 0.64f), style = st)
                poly(false, 0.5f to 0.2f, 0.5f to 0.84f)
            }
            Ic.SWAP -> {
                poly(false, 0.14f to 0.32f, 0.86f to 0.32f)
                poly(false, 0.68f to 0.16f, 0.86f to 0.32f, 0.68f to 0.48f)
                poly(false, 0.86f to 0.68f, 0.14f to 0.68f)
                poly(false, 0.32f to 0.52f, 0.14f to 0.68f, 0.32f to 0.84f)
            }
            Ic.STAR -> {
                val p = Path()
                p.moveTo(0.5f * s, 0.1f * s)
                p.cubicTo(0.52f * s, 0.38f * s, 0.62f * s, 0.48f * s, 0.9f * s, 0.5f * s)
                p.cubicTo(0.62f * s, 0.52f * s, 0.52f * s, 0.62f * s, 0.5f * s, 0.9f * s)
                p.cubicTo(0.48f * s, 0.62f * s, 0.38f * s, 0.52f * s, 0.1f * s, 0.5f * s)
                p.cubicTo(0.38f * s, 0.48f * s, 0.48f * s, 0.38f * s, 0.5f * s, 0.1f * s)
                p.close()
                drawPath(p, tint)
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ building blocks

/** Round logo. If you add a picture named nisu_logo (png / webp) to res/drawable it is used; otherwise a glowing badge is drawn. */
@Composable
fun NisuLogo(dp: Dp) {
    val ctx = LocalContext.current
    // v2.52: the anime avatar is the logo everywhere; nisu_logo (if someone adds it) and the drawn badge are only fallbacks
    val res = remember {
        ctx.resources.getIdentifier("nisu_avatar", "drawable", ctx.packageName).takeIf { it != 0 }
            ?: ctx.resources.getIdentifier("nisu_logo", "drawable", ctx.packageName)
    }
    if (res != 0) {
        Image(painterResource(res), contentDescription = "NISU AI", contentScale = ContentScale.Crop,
            modifier = Modifier.size(dp).clip(CircleShape).border((dp.value / 22f).coerceAtLeast(1.5f).dp, NisuUi.GoldBrush, CircleShape))
    } else {
        Canvas(Modifier.size(dp)) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(Color(0xFF3A2D14), Color(0xFF181208), Color(0xFF070604)), center = center, radius = r), radius = r)
            drawCircle(Brush.sweepGradient(listOf(NisuUi.Cyan, NisuUi.Violet, NisuUi.Blue, NisuUi.Cyan), center), radius = r - 1.5f, style = Stroke(width = r * 0.07f))
            val p = Path()
            p.moveTo(center.x - r * 0.45f, center.y + r * 0.35f)
            p.cubicTo(center.x - r * 0.5f, center.y - r * 0.6f, center.x + r * 0.35f, center.y - r * 0.7f, center.x + r * 0.42f, center.y + r * 0.05f)
            drawPath(p, Color(0xE6F3DDA6), style = Stroke(width = r * 0.11f, cap = StrokeCap.Round))
            drawCircle(Color(0xFFFFF3D4), radius = r * 0.09f, center = Offset(center.x + r * 0.08f, center.y - r * 0.02f))
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    // v2.83: glass version of the old navy card: same navy colour, but see-through, with a light edge and a soft shine; text stays easy to read
    val shape = RoundedCornerShape(26.dp)
    val base = modifier
        .shadow(8.dp, shape, clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x55000000))
        .clip(shape)
        .background(Brush.verticalGradient(listOf(NisuUi.CardHi.copy(alpha = 0.62f), NisuUi.Card.copy(alpha = 0.52f))))
        .drawWithContent {
            drawContent()
            drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center = Offset(size.width * 0.2f, -size.height * 0.05f), radius = size.width * 0.5f))
        }
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), NisuUi.Cyan.copy(alpha = 0.18f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.30f)), start = Offset.Zero, end = Offset.Infinite),
            shape
        )
    Box(if (onClick != null) base.clickable(onClick = onClick) else base) { content() }
}

@Composable
fun IconButtonBox(icon: Ic, round: Boolean = false, tint: Color = NisuUi.Cyan, onClick: () -> Unit) {
    val shape = if (round) CircleShape else RoundedCornerShape(16.dp)
    Box(
        Modifier.size(44.dp).clip(shape).background(Brush.verticalGradient(listOf(NisuUi.CardHi.copy(alpha = 0.62f), NisuUi.Card.copy(alpha = 0.52f))))
            .border(1.dp, tint.copy(alpha = 0.35f), shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { MIcon(icon, tint, 21.dp) }
}

/** Small tinted pill button. */
@Composable
fun MChip(text: String, color: Color = NisuUi.Cyan, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(color.copy(alpha = 0.10f)).border(1.dp, color.copy(alpha = 0.55f), shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    ) { Text(text, fontSize = 11.sp, letterSpacing = 1.2.sp, color = if (enabled) color else color.copy(alpha = 0.4f), maxLines = 1) }
}

/** Header used on every screen: left button, centre status, bolt button (opens the model menu). */
@Composable
fun NisuTopBar(left: Ic, onLeft: () -> Unit, online: Boolean, label: String, running: Boolean, onBolt: () -> Unit, onVoice: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButtonBox(left, onClick = onLeft)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(NisuUi.Card.copy(alpha = 0.6f)).border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) NisuUi.Green else if (running) NisuUi.Amber else NisuUi.Dim))
            Spacer(Modifier.width(8.dp))
            Text(label.uppercase(), color = if (online) NisuUi.Cyan else NisuUi.Muted, fontSize = 11.sp, letterSpacing = 1.6.sp)
        }
        Spacer(Modifier.weight(1f))
        if (onVoice != null) {   // voice switch: Gemini voice / ElevenLabs voice
            IconButtonBox(Ic.WAVE, round = true, onClick = onVoice)
            Spacer(Modifier.width(8.dp))
        }
        IconButtonBox(Ic.BOLT, round = true, onClick = onBolt)
    }
}

private val modelTints = listOf(Color(0xFF38B6FF), Color(0xFF8A6CFF), Color(0xFF63D6A0))

/** "Select Model" dropdown. The models are the verified Live models from LiveModels.ALL. Place it inside a full-screen Box. */
@Composable
fun BoxScope.ModelMenu(visible: Boolean, selected: String, locked: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit, top: Dp = 66.dp) {
    if (visible) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = top, end = 16.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
    ) {
        GlassCard(Modifier.width(250.dp)) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Select Model", color = NisuUi.Cyan, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                LiveModels.ALL.forEachIndexed { i, m ->
                    val on = selected == m.id
                    val tint = modelTints[i % modelTints.size]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) NisuUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable(enabled = !locked) { onPick(m.id); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)).border(1.dp, tint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                            MIcon(Ic.STAR, tint, 18.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.label, color = if (on) NisuUi.Cyan else NisuUi.Text, fontSize = 16.sp)
                            Text(m.note, color = NisuUi.Muted, fontSize = 11.sp, maxLines = 1)
                        }
                        if (on) MIcon(Ic.CHECK, NisuUi.Cyan, 18.dp)
                    }
                    if (m.id == Prefs.DEFAULT_MODEL && on) {
                        val fctx = androidx.compose.ui.platform.LocalContext.current
                        var turbo by remember { mutableStateOf(Prefs.getBool(fctx, Prefs.FAST31, true)) }
                        fun setTurbo(v: Boolean) {
                            turbo = v; Prefs.putBool(fctx, Prefs.FAST31, v); RuleSet.stamp = System.currentTimeMillis()
                            fctx.startService(Intent(fctx, AssistantService::class.java).setAction(AssistantService.ACTION_PERSONA))   // reconnect with the new setup
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MChip("TURBO", color = if (turbo) NisuUi.Cyan else NisuUi.Muted) { setTurbo(true) }
                                MChip("NORMAL", color = if (!turbo) NisuUi.Cyan else NisuUi.Muted) { setTurbo(false) }
                            }
                            Text(if (turbo) "Turbo: replies as fast as Beta Fast (no thinking pause, quicker end-of-sentence detection, lighter prompt) and every tool still works." else "Normal: thinks a little before answering, so it understands better but is slower.", color = NisuUi.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    if (m.id == LiveModels.BETA_ID && on) {
                        val bctx = androidx.compose.ui.platform.LocalContext.current
                        var bm by remember { mutableStateOf(Prefs.betaMode(bctx)) }
                        fun setBeta(v: String) {
                            bm = v; Prefs.put(bctx, Prefs.BETA_MODE, v); RuleSet.stamp = System.currentTimeMillis()
                            bctx.startService(Intent(bctx, AssistantService::class.java).setAction(AssistantService.ACTION_PERSONA))   // reconnect with the new setup
                        }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MChip("FAST", color = if (bm == "fast") NisuUi.Cyan else NisuUi.Muted) { setBeta("fast") }
                                MChip("ALL WORKED", color = if (bm == "full") NisuUi.Cyan else NisuUi.Muted) { setBeta("full") }
                            }
                            Text(if (bm == "fast") "Fast: only phone control, screen reading, messages, calls and reminders. Anything that slows the reply is off." else "All worked: every tool and connection, full prompt, full performance like the other models.", color = NisuUi.Muted, fontSize = 11.sp)
                        }
                    }
                }
                if (locked) Text("Switching, please wait...", color = NisuUi.Amber, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}

/** Voice engine switch. Needs ElevenLabs key + voice before ElevenLabs can be chosen (checked in VoicePick). */
object VoicePick {
    /** Returns false and changes nothing when ElevenLabs is picked but not set up yet. */
    fun apply(ctx: Context, engine: String): Boolean {
        if (engine == VoiceEngine.ELEVEN && !VoiceEngine.elevenReady(ctx)) return false
        Prefs.put(ctx, Prefs.ENGINE, engine)
        AppState.engine.value = engine
        if (AppState.running.value)
            ctx.startService(Intent(ctx, AssistantService::class.java).setAction(AssistantService.ACTION_VOICE).putExtra("engine", engine))
        return true
    }
}

/** "Select Voice" dropdown: Gemini voice or ElevenLabs voice, switchable at any time. Place it inside a full-screen Box. */
@Composable
fun BoxScope.VoiceEngineMenu(visible: Boolean, selected: String, elevenName: String, onPick: (String) -> Unit, onDismiss: () -> Unit, top: Dp = 66.dp) {
    if (visible) Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = top, end = 16.dp),
        enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
        exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f))
    ) {
        GlassCard(Modifier.width(250.dp)) {
            Column(Modifier.padding(vertical = 10.dp)) {
                Text("Select Voice", color = NisuUi.Cyan, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                listOf(
                    Triple(VoiceEngine.GEMINI, "Gemini voice", "Fastest, built into Gemini"),
                    Triple(VoiceEngine.ELEVEN, "ElevenLabs voice", if (elevenName.isNotBlank()) elevenName else "Female voice, needs ElevenLabs key")
                ).forEachIndexed { i, item ->
                    val on = selected == item.first
                    val tint = modelTints[i % modelTints.size]
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (on) NisuUi.Cyan.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onPick(item.first); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)).border(1.dp, tint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                            MIcon(Ic.WAVE, tint, 18.dp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.second, color = if (on) NisuUi.Cyan else NisuUi.Text, fontSize = 16.sp)
                            Text(item.third, color = NisuUi.Muted, fontSize = 11.sp, maxLines = 1)
                        }
                        if (on) MIcon(Ic.CHECK, NisuUi.Cyan, 18.dp)
                    }
                }
            }
        }
    }
}

/** Five animated bars under "is listening..." : they move with the real microphone / speaker level. */
@Composable
fun VoiceBars(level: Float, modifier: Modifier = Modifier) {
    val phase by rememberInfiniteTransition(label = "bars").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "bars-phase"
    )
    val lv = min(1f, level * 3.2f)
    val colors = listOf(NisuUi.Blue, NisuUi.Cyan, NisuUi.Violet, NisuUi.Cyan, NisuUi.Blue)
    val shape = floatArrayOf(0.35f, 0.7f, 1f, 0.7f, 0.35f)
    Canvas(modifier.size(width = 54.dp, height = 40.dp)) {
        val w = size.width / 5f
        for (i in 0 until 5) {
            val wave = 0.5f + 0.5f * sin(phase + i * 0.95f)
            val h = size.height * shape[i] * (0.22f + 0.78f * wave) * (0.4f + 0.6f * lv)
            val x = w * (i + 0.5f)
            drawLine(colors[i], Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** "Listening • Processing • Responding" with the active step lit. active: 0, 1 or 2 (-1 = none). */
@Composable
fun StepsRow(active: Int) {
    val names = listOf("Listening", "Processing", "Responding")
    Row(verticalAlignment = Alignment.CenterVertically) {
        names.forEachIndexed { i, n ->
            if (i > 0) {
                Spacer(Modifier.width(12.dp))
                Box(Modifier.size(3.dp).clip(CircleShape).background(NisuUi.Blue.copy(alpha = 0.7f)))
                Spacer(Modifier.width(12.dp))
            }
            Text(n.uppercase(), fontSize = 11.sp, letterSpacing = 1.8.sp, color = if (i == active) NisuUi.Cyan else NisuUi.Dim)
        }
    }
}

/** Soft flowing lines used as the footer decoration of the home screen. */
@Composable
fun WaveFooter(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val brush = Brush.horizontalGradient(listOf(Color.Transparent, NisuUi.Blue.copy(alpha = 0.55f), NisuUi.Cyan.copy(alpha = 0.35f), Color.Transparent))
        for (k in 0..2) {
            val path = Path()
            val base = size.height * (0.35f + 0.15f * k)
            for (i in 0..40) {
                val x = size.width * i / 40f
                val y = base + sin(i / 40f * 2f * PI.toFloat() + k * 0.9f) * size.height * 0.16f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, brush, style = Stroke(width = 1.2f.dp.toPx() * (1f - k * 0.25f)))
        }
    }
}

/** The NISU AI wordmark from the design: glowing voice-wave bars + "NISU AI" + handwritten tagline. */
@Composable
fun NisuWordmark(modifier: Modifier = Modifier, venom: Boolean = false, jarvis: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 44.dp, height = 46.dp)) {
                val hs = floatArrayOf(0.22f, 0.5f, 0.78f, 1f, 0.78f, 0.5f, 0.22f)
                val w = size.width / hs.size
                for (i in hs.indices) {
                    val h = size.height * hs[i]
                    val x = w * (i + 0.5f)
                    val top = if (venom) VenomTheme.Silver else NisuUi.Cyan   // v3.87: Venom mode: silver bars
                    val br = Brush.verticalGradient(listOf(top, if (venom) VenomTheme.SilverDim else NisuUi.Blue), startY = size.height / 2f - h / 2f, endY = size.height / 2f + h / 2f)
                    drawLine(top.copy(alpha = 0.25f), Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), strokeWidth = 7.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(br, Offset(x, size.height / 2f - h / 2f), Offset(x, size.height / 2f + h / 2f), strokeWidth = 3.4.dp.toPx(), cap = StrokeCap.Round)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    if (venom) withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White)) { append(VenomTheme.NAME.uppercase()) }   // v3.87: the name shown in Venom mode
                    else if (jarvis) {   // v3.89: the name shown in Jarvis mode
                        withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White)) { append(JarvisTheme.NAME.uppercase()) }
                    } else {
                        withStyle(androidx.compose.ui.text.SpanStyle(color = NisuUi.Text)) { append("NISU ") }
                        withStyle(androidx.compose.ui.text.SpanStyle(color = NisuUi.Cyan)) { append("AI") }
                    }
                },
                fontSize = 34.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, letterSpacing = 1.sp,
                style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(if (venom) Color(0x99FFFFFF) else Color(0x6638B6FF), Offset.Zero, 22f))
            )
        }
        Text(if (venom) VenomTheme.TAGLINE else if (jarvis) JarvisTheme.TAGLINE else "Your Voice, My World", fontSize = 15.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive, color = NisuUi.Text.copy(alpha = 0.85f), modifier = Modifier.padding(top = 2.dp))
    }
}

/** Glass pill used for the top-right buttons of the home screen (icon + text + optional trailing icon). */
@Composable
fun GlassPill(modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xCC0B1B3D), Color(0xCC050E22))))
            .border(1.dp, Brush.linearGradient(listOf(NisuUi.Cyan.copy(alpha = 0.85f), NisuUi.Blue.copy(alpha = 0.45f))), shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { content() }
}


// ===================================================================================================== v2.78 "liquid glass" buttons

/**
 * Clear-glass look: the fill is almost fully see-through (the picture shows straight through, like a real pane of glass),
 * only a very faint tint, a thin bright edge that fades around the shape, one small crisp shine near the top and a soft shadow.
 */
fun Modifier.liquidGlass(shape: Shape, tint: Color = Color.White, shadowDp: Dp = 10.dp): Modifier = this
    .shadow(shadowDp, shape, clip = false, ambientColor = Color(0x22000000), spotColor = Color(0x55000000))
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.035f), tint.copy(alpha = 0.012f), Color.White.copy(alpha = 0.008f))))
    .drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        // small, crisp shine on the upper edge (not a milky wash)
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.13f), Color.Transparent), center = Offset(w * 0.28f, -h * 0.10f), radius = w * 0.36f))
        drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent), center = Offset(w * 0.85f, h * 1.05f), radius = w * 0.28f))
    }
    .border(
        1.2.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.08f), Color.White.copy(alpha = 0.05f), Color.White.copy(alpha = 0.42f)), start = Offset.Zero, end = Offset.Infinite),
        shape
    )

/** Text that sits on glass: bright white with a soft shadow so it stays crisp on any picture. */
@Composable
fun GlassText(text: String, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, letterSpacing: androidx.compose.ui.unit.TextUnit = 0.8.sp,
              weight: androidx.compose.ui.text.font.FontWeight = androidx.compose.ui.text.font.FontWeight.Medium) {
    Text(
        text, modifier, fontSize = fontSize, letterSpacing = letterSpacing, fontWeight = weight, maxLines = 1, color = Color.White.copy(alpha = 0.96f),
        style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color(0x66000000), Offset(0f, 2f), 6f))
    )
}

/** Round liquid-glass button. */
@Composable
fun GlassCircle(size: Dp, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false, content: @Composable () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.92f else 1f, label = "press")
    Box(
        modifier.size(size).graphicsLayer { scaleX = sc; scaleY = sc }
            .liquidGlass(CircleShape, accent.copy(alpha = if (active) 0.40f else 0.12f), 8.dp)
            // a faint smoky sky-blue under the glass so the round buttons stay visible over bright parts of the picture (e.g. blossoms)
            .background(Brush.verticalGradient(listOf(Color(0x3338B6FF), Color(0x40122A55))))
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

class GlassSeg(val label: String, val onClick: () -> Unit, val accent: Color = Color.White, val icon: @Composable () -> Unit)

/** Several separate clear-glass pill buttons side by side (icon + label), each its own rounded button with a gap between them. */
@Composable
fun GlassSegmented(items: List<GlassSeg>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { seg ->
            val src = remember { MutableInteractionSource() }
            val pressed by src.collectIsPressedAsState()
            val sc by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")
            Row(
                Modifier.graphicsLayer { scaleX = sc; scaleY = sc }
                    .height(44.dp)
                    .liquidGlass(RoundedCornerShape(50), seg.accent.copy(alpha = 0.16f), 8.dp)
                    .clickable(interactionSource = src, indication = null, onClick = seg.onClick)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                seg.icon()
                Spacer(Modifier.width(8.dp))
                GlassText(seg.label, 11.sp, letterSpacing = 1.1.sp)
            }
        }
    }
}

@Composable
fun GlyphStop(tint: Color, dp: Dp = 14.dp) {
    Canvas(Modifier.size(dp)) {
        drawRoundRect(tint, Offset(size.width * 0.15f, size.height * 0.15f), Size(size.width * 0.7f, size.height * 0.7f), CornerRadius(size.width * 0.16f))
    }
}

@Composable
fun GlyphMoon(tint: Color, dp: Dp = 15.dp) {
    Canvas(Modifier.size(dp)) {
        val w = size.width
        drawArc(tint, 45f, 280f, false, Offset(w * 0.14f, w * 0.14f), Size(w * 0.72f, w * 0.72f), style = Stroke(width = w * 0.15f, cap = StrokeCap.Round))
    }
}

@Composable
fun GlyphMuted(tint: Color, dp: Dp = 16.dp) {
    Box(Modifier.size(dp)) {
        MIcon(Ic.MIC, tint, dp)
        Canvas(Modifier.size(dp)) {
            drawLine(tint, Offset(size.width * 0.14f, size.height * 0.88f), Offset(size.width * 0.88f, size.height * 0.12f), strokeWidth = size.width * 0.11f, cap = StrokeCap.Round)
        }
    }
}

/** The microphone button: a bigger liquid-glass orb tinted blue, violet and pink; its ring swells with the voice while running. */
@Composable
fun MicOrb(running: Boolean, muted: Boolean, level: Float, ring: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.93f else 1f, label = "press")
    val lv by animateFloatAsState(if (running && !muted) level.coerceIn(0f, 1f) else 0f, label = "orbLevel")
    val pink = Color(0xFFFF6FD8)
    val c1 = if (muted) NisuUi.Amber else NisuUi.Cyan
    val c2 = if (muted) Color(0xFFFFD27A) else NisuUi.Violet
    val c3 = if (muted) NisuUi.Amber else pink
    Box(modifier.size(92.dp).graphicsLayer { scaleX = sc; scaleY = sc }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(92.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(c2.copy(alpha = 0.20f + lv * 0.3f), Color.Transparent), center = c, radius = base), radius = base, center = c)
            if (running) drawCircle(Brush.sweepGradient(listOf(c1, c2, c3, c1), center = c), radius = base * (0.80f + lv * 0.18f), center = c, alpha = 0.35f + lv * 0.5f, style = Stroke(width = 1.6.dp.toPx()))
        }
        Box(
            Modifier.size(66.dp)
                .colorGlass(CircleShape, c1, c2, c3, 12.dp)
                .clickable(interactionSource = src, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { if (muted) GlyphMuted(Color.White, 28.dp) else MIcon(Ic.MIC, Color.White, 28.dp) }
    }
}

/** A clear chat-bubble icon (speech bubble with a tail and three dots). */
@Composable
fun GlyphChat(tint: Color, dp: Dp = 20.dp) {
    Canvas(Modifier.size(dp)) {
        val s = size.minDimension
        val r = 0.12f
        val path = Path().apply {
            moveTo((0.1f + r) * s, 0.15f * s)
            lineTo((0.9f - r) * s, 0.15f * s); quadraticBezierTo(0.9f * s, 0.15f * s, 0.9f * s, (0.15f + r) * s)
            lineTo(0.9f * s, (0.72f - r) * s); quadraticBezierTo(0.9f * s, 0.72f * s, (0.9f - r) * s, 0.72f * s)
            lineTo(0.42f * s, 0.72f * s); lineTo(0.2f * s, 0.92f * s); lineTo(0.24f * s, 0.72f * s)
            lineTo((0.1f + r) * s, 0.72f * s); quadraticBezierTo(0.1f * s, 0.72f * s, 0.1f * s, (0.72f - r) * s)
            lineTo(0.1f * s, (0.15f + r) * s); quadraticBezierTo(0.1f * s, 0.15f * s, (0.1f + r) * s, 0.15f * s)
            close()
        }
        drawPath(path, tint, style = Stroke(width = s * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (x in floatArrayOf(0.32f, 0.5f, 0.68f)) drawCircle(tint, s * 0.05f, Offset(x * s, 0.435f * s))
    }
}

/** Pure coloured glass (no white at all): almost clear, tinted only by the three colours, with a coloured rim and a faint coloured shine. */
fun Modifier.colorGlass(shape: Shape, c1: Color, c2: Color, c3: Color, shadowDp: Dp = 12.dp): Modifier = this
    .shadow(shadowDp, shape, clip = false, ambientColor = Color(0x22000000), spotColor = c2.copy(alpha = 0.55f))
    .clip(shape)
    .background(Brush.linearGradient(listOf(c1.copy(alpha = 0.10f), c2.copy(alpha = 0.05f), c3.copy(alpha = 0.10f))))
    .drawWithContent {
        drawContent()
        val w = size.width
        val h = size.height
        drawRect(Brush.radialGradient(listOf(c1.copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.28f, -h * 0.10f), radius = w * 0.40f))
        drawRect(Brush.radialGradient(listOf(c3.copy(alpha = 0.16f), Color.Transparent), center = Offset(w * 0.85f, h * 1.05f), radius = w * 0.34f))
    }
    .border(1.4.dp, Brush.sweepGradient(listOf(c1.copy(alpha = 0.85f), c2.copy(alpha = 0.85f), c3.copy(alpha = 0.85f), c1.copy(alpha = 0.85f))), shape)
