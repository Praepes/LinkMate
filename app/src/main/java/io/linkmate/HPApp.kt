package io.linkmate

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class HPApp : Application() {
    // This class will be used by Hilt to generate the necessary dependency injection components.
}