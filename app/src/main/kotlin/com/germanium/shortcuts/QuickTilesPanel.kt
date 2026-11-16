package com.germanium.shortcuts

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.germanium.shortcuts.core.Shortcut

@Composable
internal fun QuickTilesPanel(
    shortcuts: List<Shortcut>,
    onRunShortcut: (Shortcut) -> Unit,
    onNotice: (String) -> Unit,
    onReport: (Throwable) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var selectingSlot by remember { mutableStateOf<Int?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Explanatory Banner
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppIcon(R.drawable.ic_controls)
                    Text(
                        "Android Quick Settings Tiles",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text(
                    "Assign your favorite applets to system Quick Tiles for instant 1-tap access from anywhere — even while your phone is locked or inside other apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Text(
            "Tile Slots (4 Available)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )

        ShortcutTiles.services.indices.forEach { slot ->
            key(slot, refreshKey) {
                val assignedId = ShortcutTiles.shortcutId(context, slot)
                val assignedApplet = shortcuts.find { it.id == assignedId }

                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (assignedApplet != null) MaterialTheme.colorScheme.surfaceContainerHigh
                        else MaterialTheme.colorScheme.surfaceContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (assignedApplet != null) {
                                ShortcutBadge(assignedApplet.icon, assignedApplet.color, 48.dp)
                            } else {
                                Surface(
                                    modifier = Modifier.size(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("${slot + 1}", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Tile Slot ${slot + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    assignedApplet?.name ?: "Empty (No Applet Assigned)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            if (assignedApplet != null) {
                                IconButton(
                                    onClick = { onRunShortcut(assignedApplet) },
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    AppIcon(R.drawable.ic_play, description = "Run")
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { selectingSlot = slot },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(if (assignedApplet == null) "Assign Applet" else "Change")
                            }

                            if (assignedApplet != null) {
                                if (Build.VERSION.SDK_INT >= 33 && activity != null) {
                                    FilledTonalButton(
                                        onClick = {
                                            ShortcutTiles.add(activity, assignedApplet, slot, onNotice, onReport)
                                        }
                                    ) {
                                        Text("Add to Drawer")
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        ShortcutTiles.remove(context, assignedApplet.id)
                                        refreshKey++
                                        onNotice("Tile cleared")
                                    }
                                ) {
                                    Text("Clear")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectingSlot?.let { slot ->
        AlertDialog(
            onDismissRequest = { selectingSlot = null },
            title = { Text("Assign Applet to Tile Slot ${slot + 1}") },
            text = {
                if (shortcuts.isEmpty()) {
                    Text("You don't have any applets yet. Vibecode one first!")
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 350.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shortcuts.size) { i ->
                            val item = shortcuts[i]
                            Card(
                                onClick = {
                                    ShortcutTiles.assign(context, slot, item.id)
                                    selectingSlot = null
                                    refreshKey++
                                    onNotice("Assigned '${item.name}' to Tile ${slot + 1}")
                                },
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    ShortcutBadge(item.icon, item.color, 36.dp)
                                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectingSlot = null }) { Text("Cancel") }
            }
        )
    }
}
