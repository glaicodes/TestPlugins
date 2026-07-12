package com.a.rezeroizle

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ReZeroIzlePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ReZeroIzleProvider())
    }
}
