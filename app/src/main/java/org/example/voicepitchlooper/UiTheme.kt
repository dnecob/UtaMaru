package org.example.voicepitchlooper

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/** Visual theme only; audio, pitch, score and connection preferences are unchanged. */
object UiTheme {
    private const val PREFS = "utamaru_ui_theme"
    private const val KEY = "mode"
    val choices = listOf("auto", "light", "dark")

    fun selected(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "auto")?.takeIf { it in choices } ?: "auto"

    fun save(context: Context, mode: String) {
        require(mode in choices)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode).apply()
    }

    fun isDark(context: Context): Boolean = when (selected(context)) {
        "dark" -> true
        "light" -> false
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    data class Palette(
        val background: Int,
        val surface: Int,
        val staffBackground: Int,
        val ink: Int,
        val muted: Int,
        val accent: Int,
        val toolbar: Int,
        val panel: Int,
        val selector: Int,
        val border: Int,
        val track: Int,
        val selection: Int,
        val secondaryInk: Int,
        val low: Int,
        val high: Int,
        val handleOutline: Int
    )

    private fun rgb(code: String): Int = Color.parseColor(code)

    fun palette(context: Context) = if (isDark(context)) Palette(
        background = rgb("#11141B"),
        surface = rgb("#171D27"),
        staffBackground = rgb("#1B2430"),
        ink = rgb("#F0F3F8"),
        muted = rgb("#ADB9CA"),
        accent = rgb("#86B5FF"),
        toolbar = rgb("#212C3C"),
        panel = rgb("#233750"),
        selector = rgb("#293344"),
        border = rgb("#75849A"),
        track = rgb("#5D6D81"),
        selection = rgb("#2D4565"),
        secondaryInk = rgb("#AEBFD5"),
        low = rgb("#87B6FF"),
        high = rgb("#63E2C3"),
        handleOutline = rgb("#E8EFF8")
    ) else Palette(
        background = rgb("#F7F9FC"),
        surface = rgb("#FAFBFD"),
        staffBackground = rgb("#FDFEFF"),
        ink = rgb("#16243B"),
        muted = rgb("#536379"),
        accent = rgb("#246BE0"),
        toolbar = rgb("#EBF2FC"),
        panel = rgb("#E4EFFF"),
        selector = rgb("#FFFFFF"),
        border = rgb("#899CB8"),
        track = rgb("#B7C2D1"),
        selection = rgb("#E6F0FF"),
        secondaryInk = rgb("#687486"),
        low = rgb("#1762C4"),
        high = rgb("#0C8B75"),
        handleOutline = rgb("#FFFFFF")
    )
}
