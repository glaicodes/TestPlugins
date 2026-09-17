package com.a.anizm

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

/**
 * User-facing settings, shown from CloudStream's Extensions screen via Plugin.openSettings.
 *
 * Stored in plain SharedPreferences (not CloudStream's getKey/setKey) so this doesn't depend
 * on app-internal APIs that have moved between releases. The UI is built in code, so the
 * plugin needs no XML resources (requiresResources stays false).
 *
 * The provider reads these on every loadLinks call, so changes apply to the next episode
 * opened — no app restart.
 */
class AnizmSettings(private val prefs: SharedPreferences) {

    /** A player family, matched against the site's player button label (case-insensitive). */
    data class SourceGroup(val key: String, val label: String, val keywords: List<String>)

    companion object {
        const val PREFS_NAME = "anizm_settings"
        const val OTHER = "other"

        /**
         * Order matters twice: first keyword match wins when classifying a label, and list
         * position is the lazy-resolve priority (earlier = tried first). "other" must be last.
         */
        val GROUPS = listOf(
            SourceGroup("aincrad", "Aincrad", listOf("aincrad")),
            SourceGroup("beta", "Beta Player", listOf("beta")),
            SourceGroup("gdrive", "Google Drive", listOf("gdrive", "google", "drive")),
            SourceGroup("voe", "Voe", listOf("voe")),
            SourceGroup("sibnet", "Sibnet", listOf("sibnet")),
            SourceGroup("okru", "Odnoklassniki (ok.ru)", listOf("ok.ru", "okru", "odnoklassniki")),
            SourceGroup("vidmoly", "Vidmoly", listOf("vidmoly")),
            SourceGroup("dood", "DoodStream", listOf("dood")),
            SourceGroup("mp4upload", "Mp4Upload", listOf("mp4upload")),
            SourceGroup("uqload", "UQload", listOf("uqload")),
            SourceGroup("sendvid", "SendVid", listOf("sendvid")),
            SourceGroup("hdvid", "HDVid", listOf("hdvid")),
            SourceGroup("abyss", "Abyss", listOf("abyss")),
            SourceGroup(OTHER, "Other players (LuluStream, Sistenn, FireStream…)", emptyList()),
        )

        private val TARGET_CHOICES = listOf(1, 2, 3, 5)
        private val MIN_QUALITY_CHOICES = listOf(0 to "Any", 720 to "720p", 1080 to "1080p")

        fun showDialog(context: Context, settings: AnizmSettings) {
            val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt() }

            val root = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), dp(8))
            }
            fun header(text: String) = TextView(context).apply {
                this.text = text
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(0, dp(14), 0, dp(2))
            }.also { root.addView(it) }
            fun note(text: String) = TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                alpha = 0.75f
                setPadding(0, 0, 0, dp(4))
            }.also { root.addView(it) }
            fun check(text: String, checked: Boolean) = CheckBox(context).apply {
                this.text = text
                isChecked = checked
            }.also { root.addView(it) }

            header("Sources")
            note("Matched by the player's button name on anizm. Order below = the order sources are tried in.")
            val sourceBoxes = GROUPS.map { g -> g.key to check(g.label, settings.isSourceEnabled(g.key)) }
            val lastResortBox = check("If no enabled source works, also try disabled ones", settings.tryDisabledAsLastResort)

            header("Loading")
            val lazyBox = check("Lazy loading: stop once enough sources work", settings.lazyResolve)
            val targetRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(32), 0, 0, 0)
            }
            targetRow.addView(TextView(context).apply { text = "Working sources needed: " })
            val spinner = Spinner(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, TARGET_CHOICES.map { it.toString() })
                setSelection(TARGET_CHOICES.indexOf(settings.lazyTargetSources).coerceAtLeast(0))
            }
            targetRow.addView(spinner)
            root.addView(targetRow)
            note("Fewer = fewer requests to the site and faster start, but fewer choices in the source list. Turn lazy loading off to always list every source.")
            val minQRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(32), 0, 0, 0)
            }
            minQRow.addView(TextView(context).apply { text = "Only count sources of at least: " })
            val minQSpinner = Spinner(context).apply {
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, MIN_QUALITY_CHOICES.map { it.second })
                setSelection(MIN_QUALITY_CHOICES.indexOfFirst { it.first == settings.lazyMinQuality }.coerceAtLeast(0))
            }
            minQRow.addView(minQSpinner)
            root.addView(minQRow)
            note("Lower-quality sources are still listed, they just don't stop the search. If nothing reaches this quality, everything that was found is kept.")
            val gdriveFirstBox = check("Try Google Drive in the first batch", settings.gdriveInFirstWave)
            note("Drive is often the fansub's original file (higher bitrate than Aincrad's 1080p). Starts a little slower: Drive links are fetched one at a time.")
            fun syncTargetRow() {
                val on = lazyBox.isChecked
                spinner.isEnabled = on; minQSpinner.isEnabled = on; gdriveFirstBox.isEnabled = on
                targetRow.alpha = if (on) 1f else 0.4f; minQRow.alpha = targetRow.alpha; gdriveFirstBox.alpha = targetRow.alpha
            }
            syncTargetRow()
            lazyBox.setOnCheckedChangeListener { _, _ -> syncTargetRow() }

            header("Extras")
            val sizeBox = check("Show estimated file size for Aincrad / Beta Player", settings.estimateSizes)
            note("Costs ~15 tiny extra requests per source and up to 4s before that source appears. Google Drive sizes are exact and need no extra requests.")

            val scroll = ScrollView(context).apply { addView(root) }

            AlertDialog.Builder(context)
                .setTitle("Anizm settings")
                .setView(scroll)
                .setPositiveButton("Save") { _, _ ->
                    val e = settings.prefs.edit()
                    for ((key, box) in sourceBoxes) e.putBoolean("src_$key", box.isChecked)
                    e.putBoolean("try_disabled_last_resort", lastResortBox.isChecked)
                    e.putBoolean("lazy_resolve", lazyBox.isChecked)
                    e.putInt("lazy_target", TARGET_CHOICES.getOrElse(spinner.selectedItemPosition) { 2 })
                    e.putInt("lazy_min_quality", MIN_QUALITY_CHOICES.getOrElse(minQSpinner.selectedItemPosition) { 1080 to "" }.first)
                    e.putBoolean("gdrive_first_wave", gdriveFirstBox.isChecked)
                    e.putBoolean("estimate_sizes", sizeBox.isChecked)
                    e.apply()
                }
                .setNeutralButton("Defaults") { _, _ -> settings.prefs.edit().clear().apply() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun isSourceEnabled(key: String) = prefs.getBoolean("src_$key", true)
    val tryDisabledAsLastResort get() = prefs.getBoolean("try_disabled_last_resort", true)
    val lazyResolve get() = prefs.getBoolean("lazy_resolve", true)
    val lazyTargetSources get() = prefs.getInt("lazy_target", 2).coerceAtLeast(1)
    val estimateSizes get() = prefs.getBoolean("estimate_sizes", true)
    /** 0 = any quality counts toward the lazy target. */
    val lazyMinQuality get() = prefs.getInt("lazy_min_quality", 1080)
    val gdriveInFirstWave get() = prefs.getBoolean("gdrive_first_wave", true)

    /** Which group a player button label belongs to. */
    fun groupOf(label: String): SourceGroup {
        val l = label.lowercase()
        return GROUPS.firstOrNull { g -> g.keywords.any { l.contains(it) } } ?: GROUPS.last()
    }
    fun priorityOf(label: String): Int = GROUPS.indexOf(groupOf(label))
    fun isEnabled(label: String) = isSourceEnabled(groupOf(label).key)
    fun isRecognised(label: String) = groupOf(label).key != OTHER
}
