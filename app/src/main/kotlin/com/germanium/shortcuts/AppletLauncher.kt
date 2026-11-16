package com.germanium.shortcuts

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import com.germanium.shortcuts.core.Shortcut

object AppletLauncher {
    fun isPinningSupported(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            shortcutManager?.isRequestPinShortcutSupported == true
        } else false
    }

    fun pinToHomeScreen(activity: Activity, shortcut: Shortcut, onResult: (Boolean, String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = activity.getSystemService(ShortcutManager::class.java)
            if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported) {
                onResult(false, "Launcher does not support pinned shortcuts")
                return
            }

            val launchIntent = Intent(activity, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("run_shortcut_id", shortcut.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val iconRes = ShortcutStyles.icons[shortcut.icon] ?: R.drawable.ic_glyph_bolt
            val shortcutInfo = ShortcutInfo.Builder(activity, "applet_${shortcut.id}")
                .setShortLabel(shortcut.name)
                .setLongLabel(shortcut.name)
                .setIcon(Icon.createWithResource(activity, iconRes))
                .setIntent(launchIntent)
                .build()

            val callbackIntent = shortcutManager.createShortcutResultIntent(shortcutInfo)
            val successCallback = PendingIntent.getBroadcast(
                activity,
                shortcut.id.hashCode(),
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val success = shortcutManager.requestPinShortcut(shortcutInfo, successCallback.intentSender)
            if (success) {
                onResult(true, "Pinned '${shortcut.name}' to home screen!")
            } else {
                onResult(false, "Failed to request home screen shortcut")
            }
        } else {
            onResult(false, "Requires Android 8.0+")
        }
    }
}
