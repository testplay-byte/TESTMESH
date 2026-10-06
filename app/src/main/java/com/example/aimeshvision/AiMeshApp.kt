package com.example.aimeshvision

import android.app.Application
import com.example.aimeshvision.crash.CrashHandler

/**
 * Application entry point: installs offline crash management before any
 * Activity, Service, or inference code can run.
 */
class AiMeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
    }
}
