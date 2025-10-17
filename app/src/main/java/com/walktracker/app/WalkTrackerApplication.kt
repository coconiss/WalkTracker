package com.walktracker.app

import android.app.Application
import org.osmdroid.config.Configuration

class WalkTrackerApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // OSMDroid 설정
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )

        Configuration.getInstance().userAgentValue = packageName
    }
}