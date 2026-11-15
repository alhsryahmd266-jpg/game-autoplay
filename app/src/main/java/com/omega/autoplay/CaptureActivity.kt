package com.omega.autoplay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/** Invisible activity that asks for the screen-capture permission and hands it to [BotService]. */
class CaptureActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 77)
    }

    @Deprecated("framework callback")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 77 && res == RESULT_OK && data != null) {
            val i = Intent(this, BotService::class.java)
                .setAction(BotService.ACT_START)
                .putExtra(BotService.EX_CODE, res)
                .putExtra(BotService.EX_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            Toast.makeText(this, "لازم توافق على التقاط الشاشة عشان البوت يشوف", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
