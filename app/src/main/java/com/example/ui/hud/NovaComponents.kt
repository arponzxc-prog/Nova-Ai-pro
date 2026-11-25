/*
 * Derived in design from the JARVIS UI (liquid-glow backgrounds, glass cards, curved dock with a centre mic button:
 * layout_*.xml / bg_liquid_glow_* / dock drawables of JARVIS AI by Tech GPTx, TAMCL v1.0 — see THIRD_PARTY_NOTICES.md).
 * No JARVIS XML or drawable is copied: rebuilt as Compose. New code © ARPON.
 */
package com.example.ui.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalNovaTokens

/** Three soft colour blobs behind the content. Static on purpose: zero animation cost, nothing to pause. */
@Composable
fun LiquidGlowBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val t = LocalNovaTokens.current
    Box(modifier.fillMaxSize().background(t.bg)) {
        Canvas(Modifier.fillMaxSize()) {
            val a = if (t.isLight) 0.28f else 0.22f
            fun blob(color: Color, cx: Float, cy: Float, r: Float) =
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha = a), Color.Transparent), Offset(size.width * cx, size.height * cy), r), r, Offset(size.width * cx, size.height * cy))
            val r = maxOf(size.width, size.height) * 0.55f
            blob(t.accent1, 0.15f, 0.12f, r)
            blob(t.accent2, 0.95f, 0.45f, r * 0.9f)
            blob(t.accent3, 0.30f, 0.95f, r)
        }
        content()
    }
}

/** Glass-morphism card: translucent gradient + hairline border, opaque enough to keep text contrast. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalNovaTokens.current
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.clip(shape)
            .background(t.surface, shape)
            .background(Brush.verticalGradient(listOf(t.cardGlassStart, t.cardGlassEnd)), shape)
            .then(Modifier.padding(1.dp))
            .background(Color.Transparent, shape)
            .padding(16.dp),
        content = content
    )
}

data class DockTab(val id: String, val label: String, val icon: ImageVector)

/** Dock with a raised centre mic button. [tabs] are split around the mic (first half left, rest right). */
@Composable
fun NovaDock(
    tabs: List<DockTab>, selectedId: String, micDescription: String,
    onTab: (String) -> Unit, onMic: () -> Unit, modifier: Modifier = Modifier
) {
    val t = LocalNovaTokens.current
    val left = tabs.take(tabs.size / 2)
    val right = tabs.drop(tabs.size / 2)
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(32.dp), color = t.surface, border = BorderStroke(1.dp, t.dockBorder.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.background(Brush.verticalGradient(listOf(t.dockGlassStart, t.dockGlassEnd))).heightIn(min = 68.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                left.forEach { DockItem(it, it.id == selectedId, onTab, Modifier.weight(1f)) }
                Spacer(Modifier.width(72.dp)) // room for the raised mic
                right.forEach { DockItem(it, it.id == selectedId, onTab, Modifier.weight(1f)) }
            }
        }
        Box(
            Modifier.offset(y = (-18).dp).size(64.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(t.accent1, t.accent2)))
                .clickable(role = Role.Button, onClick = onMic)
                .semantics { contentDescription = micDescription },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = t.onAccent, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun DockItem(tab: DockTab, selected: Boolean, onTab: (String) -> Unit, modifier: Modifier) {
    val t = LocalNovaTokens.current
    val color = if (selected) t.accentText else t.textSecondary
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Tab) { onTab(tab.id) }
            .semantics { this.selected = selected; contentDescription = tab.label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Icon(tab.icon, contentDescription = null, tint = color)
        Text(tab.label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
