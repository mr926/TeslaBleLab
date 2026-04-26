package com.teslablelab

import android.app.Application
import android.util.Log

class TeslaBleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        Log.d("TeslaBleApp", "TeslaBleLab application started")
    }
}
