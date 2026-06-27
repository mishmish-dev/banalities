package com.banalities.ui

actual fun platformName(): String = "Android ${android.os.Build.VERSION.SDK_INT}"
