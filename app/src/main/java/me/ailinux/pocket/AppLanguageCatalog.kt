package me.ailinux.pocket

import java.util.Locale

/**
 * Public-facing Android app locales. German is the complete base; English and additional
 * language packs deliberately start as preview (partial UI). Never localize
 * protocol method IDs, tool names, JSON schemas or raw MCP receipts.
 *
 * Application locale storage and synchronization with Android 13+ Settings are
 * owned by AppCompatDelegate/LocaleManager, not SharedPreferences.
 */
internal object AppLanguageCatalog {
    data class Choice(val tag: String, val nativeName: String, val preview: Boolean)

    val choices = listOf(
        Choice("", "System", false),
        Choice("de", "Deutsch", false),
        Choice("en", "English", true),
        Choice("fr", "Français", true),
        Choice("es", "Español", true),
        Choice("it", "Italiano", true),
        Choice("pt", "Português", true),
        Choice("nl", "Nederlands", true),
        Choice("pl", "Polski", true),
        Choice("cs", "Čeština", true),
        Choice("sk", "Slovenčina", true),
        Choice("hu", "Magyar", true),
        Choice("ro", "Română", true),
        Choice("tr", "Türkçe", true),
        Choice("ru", "Русский", true),
        Choice("uk", "Українська", true),
        Choice("ja", "日本語", true),
        Choice("ko", "한국어", true),
        Choice("zh-Hans-CN", "简体中文", true),
        Choice("zh-Hant-TW", "繁體中文", true),
        Choice("hi", "हिन्दी", true),
        Choice("id", "Bahasa Indonesia", true),
        Choice("vi", "Tiếng Việt", true),
        Choice("th", "ไทย", true)
    )

    /** Return the first requested Android locale; never treat an unknown tag as a checked translation. */
    fun selectedIndex(tags: String): Int {
        val active = tags.split(',').firstOrNull().orEmpty().trim()
        if (active.isEmpty()) return 0
        return choices.indexOfFirst {
            it.tag.isNotEmpty() && Locale.forLanguageTag(it.tag).toLanguageTag()
                .equals(Locale.forLanguageTag(active).toLanguageTag(), ignoreCase = true)
        }.coerceAtLeast(0)
    }
}
