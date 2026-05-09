package com.sty.visit.manager.di

import android.app.Application

class StyVisitApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
