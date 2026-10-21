package com.termux.gui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.io.IOException
import java.security.GeneralSecurityException

// All the app widget runtime handling is handled by the ConnectionHandler
class GUIWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context?, appWidgetManager: AppWidgetManager?, appWidgetIds: IntArray?) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (appWidgetIds != null && context != null && appWidgetManager != null) {
            for (id in appWidgetIds) {
                print("$id default")
                appWidgetManager.updateAppWidget(id, defaultViews(context))
            }
        }
    }


    @SuppressLint("ApplySharedPref")
    override fun onDeleted(context: Context?, appWidgetIds: IntArray?) {
        super.onDeleted(context, appWidgetIds)
        val p = getIDMappingPrefs(context)
        if (p != null && appWidgetIds != null) {
            val e = p.edit()
            for (id in appWidgetIds) {
                e.remove(p.getString(id.toString(), ""))
                e.remove(id.toString())
                e.remove("$id-layout")
            }
            e.commit()
        }
    }

    /*
    @SuppressLint("ApplySharedPref")
    override fun onRestored(context: Context?, oldWidgetIds: IntArray?, newWidgetIds: IntArray?) {
        super.onRestored(context, oldWidgetIds, newWidgetIds)
        val p = getIDMappingPrefs(context)
        if (context != null && p != null && oldWidgetIds != null && newWidgetIds != null && oldWidgetIds.size == newWidgetIds.size) {
            val e = p.edit()
            for (i in oldWidgetIds.indices) {
                val uuid = p.getString(oldWidgetIds[i].toString(), null)
                if (uuid != null) {
                    e.remove(oldWidgetIds[i].toString())
                    e.putInt(uuid, newWidgetIds[i])
                    e.putString(newWidgetIds[i].toString(), uuid)
                }
            }
            e.commit()
        }
    }
     */

    companion object {
        fun defaultViews(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_preview)
            
            val i = Intent(Intent.ACTION_MAIN)
            i.component = ComponentName.createRelative("com.termux", ".app.TermuxActivity")
            v.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE))
            return v
        }
        
        // 本地改动：原实现用 EncryptedSharedPreferences（拖进 tink-android，与宿主已有 tink 冲突），
        // 存的只是 widget id 映射等非敏感项，改为普通 SharedPreferences
        fun getIDMappingPrefs(c: Context?) : android.content.SharedPreferences? {
            if (c == null)
                return null
            return c.getSharedPreferences("widgets_prefs", Context.MODE_PRIVATE)
        }
        
        
    }
}