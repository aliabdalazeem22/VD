package com.example.videodl

import android.app.Application
import com.example.videodl.core.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Build the OkHttp client and engine once, off the first frame.
        ServiceLocator.engine(this)
    }
}
