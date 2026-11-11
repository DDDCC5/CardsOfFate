package com.example.cardsfate.platform

import android.os.Bundle
import android.content.pm.PackageManager
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

class LaunchActivity : SpatialLaunchActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Official optional eye-tracking permission. Denial preserves pure hand interaction.
        val permission = "com.picovr.permission.EYE_TRACKING"
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 410)
        }
    }
}
