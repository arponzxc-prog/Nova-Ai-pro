/* © ARPON — original NOVA AI code (theme picker; palettes derive from JARVIS, see NovaTheme.kt). */
package com.example.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelectionSheet(current: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    val selected = NovaThemeChoice.fromKey(current)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.theme_sheet_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.theme_sheet_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            NovaThemeChoice.entries.forEach { choice ->
                val label = stringResource(
                    when (choice) {
                        NovaThemeChoice.AUTO -> R.string.theme_auto
                        NovaThemeChoice.DARK -> R.string.theme_dark
                        NovaThemeChoice.LIGHT -> R.string.theme_light
                        NovaThemeChoice.RED -> R.string.theme_red
                        NovaThemeChoice.SUNSET -> R.string.theme_sunset
                    }
                )
                // swatch shows the real background + accent of that theme (AUTO shows dark and light halves)
                val swatchTokens = when (choice) {
                    NovaThemeChoice.AUTO -> NovaThemes.Dark
                    else -> NovaThemes.tokens(NovaThemes.resolve(choice, "system", false))
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                        .selectable(selected = choice == selected, role = Role.RadioButton, onClick = { onSelect(choice.key) })
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(swatchTokens.bg)
                            .border(BorderStroke(2.dp, swatchTokens.accent1), CircleShape)
                    )
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    RadioButton(selected = choice == selected, onClick = null)
                }
            }
        }
    }
}
