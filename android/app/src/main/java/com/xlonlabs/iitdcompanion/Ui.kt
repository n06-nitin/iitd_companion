package com.xlonlabs.iitdcompanion

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* -------------------------------- tokens -------------------------------- */
internal val Ink = Color(0xFF141518)       // app background
internal val Clay = Color(0xFF22242A)      // raised surfaces
internal val ClayWell = Color(0xFF1A1B1F)  // pressed-in wells (inputs)
internal val Bone = Color(0xFFECE7DE)      // primary text, primary button
internal val Muted = Color(0xFF8C8880)
internal val Faint = Color(0xFF5E5B56)
internal val Danger = Color(0xFFF2705E)
internal val Caution = Color(0xFFF2C14E)
private val ClayShadow = Color(0xF0040405)
private val ClayLift = Color(0x12FFFFFF)

/** Bricolage Grotesque (OFL): the title and tab labels only. */
@OptIn(ExperimentalTextApi::class)
internal val Display = FontFamily(
    Font(R.font.bricolage_grotesque, FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600), FontVariation.Setting("opsz", 14f))),
    Font(R.font.bricolage_grotesque, FontWeight.ExtraBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(800), FontVariation.Setting("opsz", 48f))),
)

/* --------------------------------- clay --------------------------------- */
/**
 * A claymorphic slab: soft dark drop shadow bottom-right, a faint lift top-left,
 * a body a touch lighter at the top, and a lit rim. [inset] draws a pressed-in
 * well instead (dark rim on top, no drop shadow).
 */
fun Modifier.clay(
    color: Color = Clay,
    radius: Dp = 22.dp,
    depth: Dp = 8.dp,
    inset: Boolean = false,
) = clay({ color }, radius, depth, inset)

/** [color] is read while drawing: animate it without recomposing. */
fun Modifier.clay(
    color: () -> Color,
    radius: Dp = 22.dp,
    depth: Dp = 8.dp,
    inset: Boolean = false,
) = drawWithCache {
    val color = color()
    val r = radius.toPx().coerceAtMost(size.minDimension / 2f)
    val d = depth.toPx()
    val corner = CornerRadius(r, r)
    val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
    }
    val body = Brush.verticalGradient(
        if (inset) listOf(lerp(color, Color.Black, 0.3f), color)
        else listOf(lerp(color, Color.White, 0.07f), lerp(color, Color.Black, 0.07f))
    )
    val rim = Brush.verticalGradient(
        if (inset) listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent, Color.White.copy(alpha = 0.07f))
        else listOf(Color.White.copy(alpha = 0.17f), Color.Transparent, Color.Black.copy(alpha = 0.3f))
    )
    val sw = 1.dp.toPx()
    onDrawBehind {
        if (!inset && d > 0f) drawIntoCanvas { canvas ->
            shadowPaint.setShadowLayer(d * 1.6f, d * 0.5f, d * 0.8f, ClayShadow.toArgb())
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, shadowPaint)
            shadowPaint.setShadowLayer(d * 1.2f, -d * 0.35f, -d * 0.45f, ClayLift.toArgb())
            canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, shadowPaint)
        }
        drawRoundRect(body, cornerRadius = corner)
        drawRoundRect(
            rim, topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(r - sw / 2, r - sw / 2), style = Stroke(sw)
        )
    }
}

@Composable
fun ClayBackground() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Ink)
        // one soft overhead light, so the clay has something to catch
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.04f), Color.Transparent),
                center = Offset(size.width * 0.25f, 0f), radius = size.width * 1.2f
            )
        )
    }
}

/* ------------------------------ components ------------------------------ */
@Composable
fun ClayCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clay(radius = 28.dp, depth = 14.dp)
            .padding(20.dp),
        content = content
    )
}

@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType,
    visual: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(18.dp)
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        visualTransformation = visual,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailing,
        interactionSource = interaction,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .clay(ClayWell, 18.dp, inset = true)
            .border(1.dp, if (focused) Bone.copy(alpha = 0.35f) else Color.Transparent, shape),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedTextColor = Bone,
            unfocusedTextColor = Bone,
            cursorColor = Bone,
            focusedLabelColor = Bone,
            unfocusedLabelColor = Muted,
            focusedPlaceholderColor = Faint,
            unfocusedPlaceholderColor = Faint,
        )
    )
}

/** A clay slab button; it sinks into the surface while pressed. */
@Composable
fun ClayButton(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Clay,
    contentColor: Color = Bone,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateDpAsState(if (pressed || !enabled) 2.dp else 8.dp, spring(dampingRatio = 0.6f), label = "depth")
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(dampingRatio = 0.5f), label = "press")
    Box(
        modifier
            .scale(scale)
            .clay(if (enabled) color else Clay, radius = 20.dp, depth = depth)
            .clip(RoundedCornerShape(20.dp))
            .clickableNoRipple(enabled, interaction, onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (enabled) contentColor else Faint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ClayIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateDpAsState(if (pressed) 1.dp else 6.dp, label = "depth")
    Box(
        Modifier
            .size(44.dp)
            .clay(radius = 22.dp, depth = depth)
            .clip(CircleShape)
            .clickableNoRipple(interaction = interaction, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Bone, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun StatusPill(label: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clay(radius = 22.dp, depth = 6.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(10.dp)
                .border(2.dp, dot.copy(alpha = 0.3f), CircleShape)
                .padding(2.dp)
                .clip(CircleShape)
                .clay(dot, radius = 5.dp, depth = 0.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, color = Bone, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

/* ------------------------------ helpers -------------------------------- */
fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    interaction: MutableInteractionSource? = null,
    onClick: () -> Unit,
) = composed {
    val src = interaction ?: remember { MutableInteractionSource() }
    clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}
