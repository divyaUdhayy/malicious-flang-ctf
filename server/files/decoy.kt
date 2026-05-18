package com.view

import android.content.Context
import android.util.Log
import android.widget.Toast
import android.os.Handler
import android.os.Looper

// Compile rules for kotlin files:
// kotlinc new_view.kt -cp /Users/[USERNAME]/Library/Android/sdk/platforms/android-36/android.jar -d new_view.jar
// Users/[USERNAME]/Library/Android/sdk/build-tools/36.1.0/d8 new_view.jar --lib /Users/chrisdegrand/Library/Android/sdk/platforms/android-36/android.jar --output .
// Rename classes.dex to new_view.dex

class Runner {
    private val hiddenData = "It is such an odd time right now. If only we could even it out."
    fun run(context: Context) {
        Log.e("Flang", "Nothing to hide here.")
    }
}