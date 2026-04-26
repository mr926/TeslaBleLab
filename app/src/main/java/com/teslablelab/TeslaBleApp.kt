package com.teslablelab

import android.app.Application
import com.jakewharton.timber.Timber
import timber.log.Timber.DebugTree

class TeslaBleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        if (Timber.forest().isEmpty()) {
            Timber.plant(DebugTree())
        }

        Timber.tag("TeslaBleApp").d("TeslaBleLab application started")
    }
}
