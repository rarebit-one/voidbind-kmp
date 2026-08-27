package one.rarebit.voidbind.app

import android.app.Application
import one.rarebit.voidbind.VoidbindAndroid

/**
 * Provides the application [android.content.Context] to the library once, so the
 * hardware [one.rarebit.voidbind.DeviceKeyStore] can persist its sealed key. Must
 * run before any keystore use — hence `Application.onCreate`.
 */
class VoidbindApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VoidbindAndroid.init(this)
    }
}
