package com.omega.autoplay

/** Invisible activity that asks for screen-capture permission for "تسجيل فريم", fully separate from CaptureActivity/BotService. */

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast

class RecCaptureActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 78)
    }

    @Deprecated("framework callback")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 78 && res == RESULT_OK && data != null) {
            val i = Intent(this, RecService::class.java)
                .setAction(RecService.ACT_START)
                .putExtra(RecService.EX_CODE, res)
                .putExtra(RecService.EX_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } else {
            Toast.makeText(this, "لازم توافق على التقاط الشاشة عشان تسجيل فريم يشتغل", Toast.LENGTH_LONG).show()
        }
        finish()
    }
}
