// ============================================================
// Anizle CloudStream 3 Extension - Plugin build.gradle.kts
// ============================================================

version = 1

cloudstream {
    // Displayed in the CloudStream extensions list
    language    = "tr"
    description = "Anizle / Anizm — Türkçe altyazılı anime akışı"
    authors     = listOf("yourname")

    /**
     * Status codes:
     *  0 = Down / not working
     *  1 = Needs a VPN
     *  2 = Slow (rate-limited)
     *  3 = Operational / all good
     */
    status  = 1 // Site may be behind Cloudflare / require VPN
    tvTypes = listOf("Anime", "AnimeMovie", "OVA")

    iconUrl = "https://anizm.pro/favicon.ico"
}
