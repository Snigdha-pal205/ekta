package com.ekta.sos

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language, chosen in Settings → Language. Every screen, alert and
 * notification uses it. (SMS texts stay in English so any contact can read them.)
 */
object Lang {
    data class Option(val tag: String, val native: String, val english: String)

    val options = listOf(
        Option("en", "English", "English"),
        Option("hi", "हिन्दी", "Hindi"),
        Option("bn", "বাংলা", "Bengali"),
        Option("te", "తెలుగు", "Telugu"),
        Option("mr", "मराठी", "Marathi"),
        Option("ta", "தமிழ்", "Tamil"),
        Option("gu", "ગુજરાતી", "Gujarati"),
        Option("kn", "ಕನ್ನಡ", "Kannada"),
        Option("ml", "മലയാളം", "Malayalam"),
        Option("pa", "ਪੰਜਾਬੀ", "Punjabi"),
        Option("or", "ଓଡ଼ିଆ", "Odia"),
    )

    private const val PREFS = "ekta"
    private const val PREF_LANG = "app_language"

    fun current(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_LANG, null)
            ?.takeIf { t -> options.any { it.tag == t } } ?: "en"

    fun currentOption(c: Context): Option = options.first { it.tag == current(c) }

    /** Save the choice and redraw the app in it. */
    fun set(activity: Activity, tag: String) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_LANG, tag).commit()
        cache = null
        if (Build.VERSION.SDK_INT >= 33) {
            // Also tell Android, so the app's language shows in the phone's settings.
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        }
        activity.recreate()
    }

    /** A context that uses the chosen language (for an Activity's / Service's attachBaseContext). */
    fun wrap(base: Context): Context {
        val tag = current(base)
        latinScript = tag == "en"
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    @Volatile private var cache: Pair<String, Context>? = null

    /** True while the app is in English (screens then use the typewriter font). */
    @Volatile var latinScript: Boolean = true
        private set

    /** The app context, in the chosen language (for code that runs outside a screen). */
    fun ctx(c: Context): Context {
        val tag = current(c)
        cache?.let { if (it.first == tag) return it.second }
        return wrap(c.applicationContext).also { cache = tag to it }
    }

    private val byTag = HashMap<String, Context>()

    /** A text in a specific language (e.g. the sender's, for an SMS relayed for her). Unknown → English. */
    fun sIn(c: Context, tag: String, id: Int, vararg args: Any): String {
        val t = tag.takeIf { x -> options.any { it.tag == x } } ?: "en"
        val ctx = synchronized(byTag) {
            byTag.getOrPut(t) {
                val config = Configuration(c.applicationContext.resources.configuration)
                config.setLocale(Locale.forLanguageTag(t))
                c.applicationContext.createConfigurationContext(config)
            }
        }
        return ctx.getString(id, *args)
    }

    /** A text in the chosen language. */
    fun s(c: Context, id: Int, vararg args: Any): String = ctx(c).getString(id, *args)
}
