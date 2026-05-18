package de.tadris.flang.ui.fragment

import android.content.res.AssetManager

object NativeSettingsBridge {
    init {
        System.loadLibrary("cflang")
    }

    external fun resolveSecret(password: String, assetManager: AssetManager): String
}