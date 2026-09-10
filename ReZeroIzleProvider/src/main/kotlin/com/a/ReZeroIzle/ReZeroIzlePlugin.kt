package com.a.rezeroizle

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ReZeroIzlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ReZeroIzleProvider())
    }
}
