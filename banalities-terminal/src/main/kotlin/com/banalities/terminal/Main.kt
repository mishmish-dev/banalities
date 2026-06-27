package com.banalities.terminal

import com.jakewharton.mosaic.runMosaic
import com.jakewharton.mosaic.ui.Text
import com.banalities.core.greeting
import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        runMosaic {
            Text(greeting("terminal"))
        }
    }
}
