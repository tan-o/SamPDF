package com.samreader.app

import android.app.Application

class SamReaderApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
