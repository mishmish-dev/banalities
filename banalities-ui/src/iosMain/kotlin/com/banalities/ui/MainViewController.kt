package com.banalities.ui

import androidx.compose.ui.window.ComposeUIViewController

// Entry point consumed by the iOS app (banalities-ios) through the BanalitiesUI framework.
fun MainViewController() = ComposeUIViewController { App() }
