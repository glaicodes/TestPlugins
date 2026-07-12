package com.a.anizle

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnizlePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnizleProvider())
    }
}
