package com.germanium.shortcuts

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal data class NavigationTabItem(val key: String, val label: String, val icon: Int)

internal val NavigationTabs = listOf(
    NavigationTabItem("applets", "Applets", R.drawable.ic_glyph_layers),
    NavigationTabItem("vibecode", "Vibecode", R.drawable.ic_glyph_bolt),
    NavigationTabItem("tiles", "Tiles", R.drawable.ic_controls),
    NavigationTabItem("automation", "Auto", R.drawable.ic_automation),
    NavigationTabItem("settings", "Settings", R.drawable.ic_settings)
)

@Composable internal fun FloatingNavigation(selectedTab: Int, select: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(shortcutsBottomInsets())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .testTag("floating_navigation")
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavigationTabs.forEachIndexed { index, tab ->
                    val chosen = selectedTab == index
                    val title = tab.label
                    val bgColor by animateColorAsState(
                        targetValue = if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        animationSpec = tween(180, easing = FastOutSlowInEasing),
                        label = "tab_bg"
                    )

                    Surface(
                        onClick = { select(index) },
                        shape = RoundedCornerShape(26.dp),
                        color = bgColor,
                        modifier = Modifier
                            .height(46.dp)
                            .testTag("nav_${tab.key}")
                            .semantics {
                                selected = chosen
                                role = Role.Tab
                                contentDescription = title
                            }
                    ) {
                        Row(
                            Modifier.padding(horizontal = if (chosen) 12.dp else 10.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AppIcon(tab.icon)
                            if (chosen) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun FloatingNavigationRail(selectedTab: Int, select: (Int) -> Unit) {
    Box(
        Modifier
            .width(104.dp)
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.Bottom))
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.testTag("floating_navigation_rail")
        ) {
            Column(
                Modifier.padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NavigationTabs.forEachIndexed { index, tab ->
                    val chosen = index == selectedTab
                    val title = tab.label
                    Surface(
                        onClick = { select(index) },
                        shape = RoundedCornerShape(26.dp),
                        color = if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        modifier = Modifier
                            .width(72.dp)
                            .heightIn(min = 60.dp)
                            .testTag("nav_${tab.key}")
                            .semantics {
                                selected = chosen
                                role = Role.Tab
                                contentDescription = title
                            }
                    ) {
                        Column(
                            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AppIcon(tab.icon)
                            if (chosen) {
                                Text(
                                    title,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
