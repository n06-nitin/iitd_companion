package com.xlonlabs.iitdcompanion

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val DOCK_SIZE = 4
private const val PREF_DOCK = "dock"

fun loadDock(prefs: SharedPreferences): List<Portal> {
    val raw = prefs.getString(PREF_DOCK, null) ?: return Portal.defaultDock
    val parsed = raw.split(',').mapNotNull { name -> Portal.pickable.firstOrNull { it.name == name } }.distinct()
    return if (parsed.size == DOCK_SIZE) parsed else Portal.defaultDock
}

fun saveDock(prefs: SharedPreferences, dock: List<Portal>) {
    prefs.edit().putString(PREF_DOCK, dock.joinToString(",") { it.name }).apply()
}

@Composable
fun EditTabsSheet(
    current: List<Portal>,
    onSave: (List<Portal>) -> Unit,
    onClose: () -> Unit,
) {
    var picked by remember(current) { mutableStateOf(current) }
    val rest = Portal.pickable.filterNot { it in picked }
    val canSave = picked.size == DOCK_SIZE

    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusPill("Tabs · ${picked.size}/$DOCK_SIZE", if (canSave) Bone else Caution, Modifier.weight(1f))
            ClayIconButton(Icons.Rounded.Close, "Close", onClose)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ClayCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    picked.forEachIndexed { i, p ->
                        TabRow(
                            portal = p,
                            selected = true,
                            canMoveUp = i > 0,
                            canMoveDown = i < picked.lastIndex,
                            onToggle = { picked = picked - p },
                            onMoveUp = {
                                picked = picked.toMutableList().apply {
                                    val t = this[i - 1]; this[i - 1] = this[i]; this[i] = t
                                }
                            },
                            onMoveDown = {
                                picked = picked.toMutableList().apply {
                                    val t = this[i + 1]; this[i + 1] = this[i]; this[i] = t
                                }
                            }
                        )
                    }
                    rest.forEach { p ->
                        TabRow(
                            portal = p,
                            selected = false,
                            canMoveUp = false,
                            canMoveDown = false,
                            onToggle = { if (picked.size < DOCK_SIZE) picked = picked + p },
                            onMoveUp = {},
                            onMoveDown = {}
                        )
                    }
                }
            }

            Text(
                "Tap to deselect and pick 4 tabs",
                color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            ClayButton(
                text = "Save",
                color = Bone,
                contentColor = Ink,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth()
            ) { onSave(picked) }

            if (picked != Portal.defaultDock) {
                Text(
                    "Reset",
                    color = Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(8.dp))
                        .clickableNoRipple { picked = Portal.defaultDock }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TabRow(
    portal: Portal,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clay(if (selected) ClayWell else Clay, radius = 16.dp, depth = if (selected) 0.dp else 4.dp, inset = selected)
            .clip(RoundedCornerShape(16.dp))
            .clickableNoRipple(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            portal.icon, contentDescription = null,
            tint = if (selected) portal.accent else Muted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            portal.label,
            color = if (selected) Bone else Muted,
            fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .alpha(if (canMoveUp) 1f else 0.25f)
                    .clickableNoRipple(enabled = canMoveUp, onClick = onMoveUp),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up", tint = Bone, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .alpha(if (canMoveDown) 1f else 0.25f)
                    .clickableNoRipple(enabled = canMoveDown, onClick = onMoveDown),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down", tint = Bone, modifier = Modifier.size(20.dp))
            }
        }
    }
}
