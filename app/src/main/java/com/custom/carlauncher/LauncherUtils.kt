package com.custom.carlauncher

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast

object LauncherUtils {

    fun openHomeSettings(context: Context) {
        val homeIntent = Intent(Settings.ACTION_HOME_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val manageAppsIntent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val generalSettingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(homeIntent)
        } catch (_: Exception) {
            try {
                context.startActivity(manageAppsIntent)
            } catch (_: Exception) {
                try {
                    context.startActivity(generalSettingsIntent)
                } catch (e: Exception) {
                    Toast.makeText(context, "Could not open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun openAllApps(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(Intent.createChooser(intent, "Select App"))
        } catch (e: Exception) {
            Toast.makeText(context, "App list error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
