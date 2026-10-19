package com.obsidianwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class QuickCaptureWidgetProvider : AppWidgetProvider() {

    companion object {
        const val EXTRA_QUICK_CAPTURE_WIDGET = "extra_quick_capture_widget"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            VaultManager.deleteWidgetPrefs(context, id)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val vaultManager = VaultManager(context, appWidgetId)
        val views = RemoteViews(context.packageName, R.layout.quick_capture_widget_layout)
        val colors = vaultManager.getThemeColors()

        views.setFloat(R.id.quick_capture_root, "setAlpha", vaultManager.widgetAlpha / 100f)
        views.setInt(R.id.quick_capture_circle, "setColorFilter", colors.accent)
        views.setInt(R.id.quick_capture_plus, "setColorFilter", colors.buttonText)

        val captureIntent = Intent(context, QuickCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(ObsidianWidgetProvider.EXTRA_WIDGET_ID, appWidgetId)
            putExtra(EXTRA_QUICK_CAPTURE_WIDGET, true)
        }
        views.setOnClickPendingIntent(
            R.id.quick_capture_root,
            PendingIntent.getActivity(
                context,
                appWidgetId,
                captureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
