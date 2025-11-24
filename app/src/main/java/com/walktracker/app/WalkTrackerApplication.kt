package com.walktracker.app

import android.app.Application
import com.google.android.gms.ads.MobileAds
import org.osmdroid.config.Configuration

class WalkTrackerApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Mobile Ads SDK
        MobileAds.initialize(this)

        // OSMDroid 설정
        Configuration.getInstance().load(
            this,
            getSharedPreferences("osmdroid", MODE_PRIVATE)
        )

        Configuration.getInstance().userAgentValue = packageName
    }
}