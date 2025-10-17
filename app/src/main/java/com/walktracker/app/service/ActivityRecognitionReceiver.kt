package com.walktracker.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityRecognitionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACTIVITY_TYPE_UPDATE = "com.walktracker.app.ACTIVITY_TYPE_UPDATE"
        const val EXTRA_ACTIVITY_TYPE = "extra_activity_type"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent)
            result?.let {
                for (event in it.transitionEvents) {
                    handleActivityTransition(context, event.activityType, event.transitionType)
                }
            }
        }
    }

    private fun handleActivityTransition(
        context: Context,
        activityType: Int,
        transitionType: Int
    ) {
        if (transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
            val activity = when (activityType) {
                DetectedActivity.WALKING -> "WALKING"
                DetectedActivity.RUNNING -> "RUNNING"
                DetectedActivity.IN_VEHICLE -> "VEHICLE"
                DetectedActivity.STILL -> "STILL"
                else -> "UNKNOWN"
            }

            val broadcastIntent = Intent(ACTION_ACTIVITY_TYPE_UPDATE).apply {
                putExtra(EXTRA_ACTIVITY_TYPE, activity)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent)
        }
    }
}