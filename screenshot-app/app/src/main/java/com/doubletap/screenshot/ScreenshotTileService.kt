package com.doubletap.screenshot

import android.content.SharedPreferences
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class ScreenshotTileService : TileService() {

    private val prefs: SharedPreferences
        get() = getSharedPreferences(ScreenshotService.PREFS_NAME, MODE_PRIVATE)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val currentlyActive = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
        prefs.edit().putBoolean(ScreenshotService.KEY_ACTIVE, !currentlyActive).apply()
        ScreenshotService.instance?.setDetectionActive(!currentlyActive)
        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isActive = prefs.getBoolean(ScreenshotService.KEY_ACTIVE, true)
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Screenshot"
        tile.subtitle = if (isActive) "On" else "Off"
        tile.updateTile()
    }
}
