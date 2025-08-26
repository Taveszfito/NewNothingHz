package com.nothing120hzunlock

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import android.content.Intent
import android.provider.Settings

class OverlayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        // Frissítjük az állapotot induláskor
        qsTile.state = if (FloatingService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile.icon = Icon.createWithResource(this, R.drawable.ic_qs_120hz)
        qsTile.label = "120HZ"
        qsTile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (FloatingService.isRunning) {
            // Ha fut → leállítjuk
            stopService(Intent(this, FloatingService::class.java))
            Toast.makeText(this, "120Hz unlock stopped.", Toast.LENGTH_SHORT).show()
            qsTile.state = Tile.STATE_INACTIVE
        } else {
            // Ha nem fut → overlay engedély kell
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, FloatingService::class.java))
                Toast.makeText(this, "120Hz unlocked.", Toast.LENGTH_SHORT).show()
                qsTile.state = Tile.STATE_ACTIVE
            } else {
                Toast.makeText(this, "Overlay permission missing!", Toast.LENGTH_SHORT).show()
            }
        }
        qsTile.icon = Icon.createWithResource(this, R.drawable.ic_qs_120hz)
        qsTile.label = "120HZ"
        qsTile.updateTile()
    }
}
