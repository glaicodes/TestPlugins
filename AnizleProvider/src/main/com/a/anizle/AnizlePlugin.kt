package com.yourname.anizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnizlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnizleProvider())
    }
}
