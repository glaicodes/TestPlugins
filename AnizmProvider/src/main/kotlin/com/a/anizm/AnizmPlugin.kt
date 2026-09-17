package com.a.anizm

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnizmPlugin : Plugin() {
    override fun load(context: Context) {
        val settings = AnizmSettings(context.getSharedPreferences(AnizmSettings.PREFS_NAME, Context.MODE_PRIVATE))
        registerMainAPI(AnizmProvider(settings))
        // Adds the gear/settings button for this extension in CloudStream's Extensions screen.
        openSettings = { ctx -> AnizmSettings.showDialog(ctx, settings) }
    }
}
