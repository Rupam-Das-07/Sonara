package com.example.sonara.domain.model

/**
 * Represents a user-selectable application display language.
 *
 * Design goals:
 * - Extensible: new languages can be added by adding enum entries without
 *   changing the persistence format (stored by name string).
 * - BCP-47 aware: each entry carries the ISO 639-1/BCP-47 tag for
 *   Android Locale / Configuration and AppCompatDelegate integration.
 * - Native display names: provides endonym / native script display where applicable.
 * - Truthful translation status: [isSupported] flags whether complete XML string
 *   resources exist for that locale.
 * - Alphabetically ordered: international entries are listed A-Z so the
 *   frontend can display them without sorting overhead.
 * - Strict separation: this only controls the application UI language.
 *   It is independent from:
 *     - lyric display language
 *     - Romanization toggle
 *     - Songs/Videos search mode
 *
 * SYSTEM_DEFAULT maps to the Android system locale — Sonara follows the device's
 * primary locale.
 */
enum class AppLanguage(
    /** Human-readable display name (English). */
    val displayName: String,
    /** Endonym / native script display name. */
    val nativeDisplayName: String,
    /**
     * BCP-47 language tag.
     * Empty string for SYSTEM_DEFAULT.
     */
    val bcp47Tag: String,
    /** Language group — national languages appear first in the UI. */
    val group: LanguageGroup,
    /** Whether full localization string resources exist in res/values-<tag>/strings.xml. */
    val isSupported: Boolean = false
) {
    // ─── System default ───────────────────────────────────────────────────────
    SYSTEM_DEFAULT(
        displayName = "System Default",
        nativeDisplayName = "System Default",
        bcp47Tag = "",
        group = LanguageGroup.SYSTEM,
        isSupported = true
    ),

    // ─── National languages (India) ───────────────────────────────────────────
    HINDI(
        displayName = "Hindi",
        nativeDisplayName = "हिन्दी",
        bcp47Tag = "hi",
        group = LanguageGroup.NATIONAL,
        isSupported = true
    ),
    BENGALI(
        displayName = "Bengali",
        nativeDisplayName = "বাংলা",
        bcp47Tag = "bn",
        group = LanguageGroup.NATIONAL,
        isSupported = true
    ),
    URDU(
        displayName = "Urdu",
        nativeDisplayName = "اردو",
        bcp47Tag = "ur",
        group = LanguageGroup.NATIONAL,
        isSupported = true
    ),

    // ─── International languages (alphabetical A → Z) ─────────────────────────
    ARABIC    (displayName = "Arabic",     nativeDisplayName = "العربية",    bcp47Tag = "ar",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    DUTCH     (displayName = "Dutch",      nativeDisplayName = "Nederlands", bcp47Tag = "nl",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    ENGLISH   (displayName = "English",    nativeDisplayName = "English",    bcp47Tag = "en",      group = LanguageGroup.INTERNATIONAL, isSupported = true),
    FRENCH    (displayName = "French",     nativeDisplayName = "Français",   bcp47Tag = "fr",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    GERMAN    (displayName = "German",     nativeDisplayName = "Deutsch",    bcp47Tag = "de",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    GUJARATI  (displayName = "Gujarati",   nativeDisplayName = "ગુજરાતી",    bcp47Tag = "gu",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    INDONESIAN(displayName = "Indonesian", nativeDisplayName = "Bahasa Indonesia", bcp47Tag = "id", group = LanguageGroup.INTERNATIONAL, isSupported = false),
    ITALIAN   (displayName = "Italian",    nativeDisplayName = "Italiano",   bcp47Tag = "it",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    JAPANESE  (displayName = "Japanese",   nativeDisplayName = "日本語",     bcp47Tag = "ja",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    KANNADA   (displayName = "Kannada",    nativeDisplayName = "ಕನ್ನಡ",      bcp47Tag = "kn",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    KOREAN    (displayName = "Korean",     nativeDisplayName = "한국어",     bcp47Tag = "ko",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    MALAYALAM (displayName = "Malayalam",  nativeDisplayName = "മലയാളം",    bcp47Tag = "ml",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    MARATHI   (displayName = "Marathi",    nativeDisplayName = "मराठी",      bcp47Tag = "mr",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    NEPALI    (displayName = "Nepali",     nativeDisplayName = "नेपाली",     bcp47Tag = "ne",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    PORTUGUESE(displayName = "Portuguese", nativeDisplayName = "Português",  bcp47Tag = "pt",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    PUNJABI   (displayName = "Punjabi",    nativeDisplayName = "ਪੰਜਾਬੀ",     bcp47Tag = "pa",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    RUSSIAN   (displayName = "Russian",    nativeDisplayName = "Русский",    bcp47Tag = "ru",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    SPANISH   (displayName = "Spanish",    nativeDisplayName = "Español",    bcp47Tag = "es",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    SWAHILI   (displayName = "Swahili",    nativeDisplayName = "Kiswahili",  bcp47Tag = "sw",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    TAMIL     (displayName = "Tamil",      nativeDisplayName = "தமிழ்",      bcp47Tag = "ta",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    TELUGU    (displayName = "Telugu",     nativeDisplayName = "తెలుగు",     bcp47Tag = "te",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    TURKISH   (displayName = "Turkish",    nativeDisplayName = "Türkçe",     bcp47Tag = "tr",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    VIETNAMESE(displayName = "Vietnamese", nativeDisplayName = "Tiếng Việt", bcp47Tag = "vi",      group = LanguageGroup.INTERNATIONAL, isSupported = false),
    CHINESE_SIMPLIFIED (displayName = "Chinese (Simplified)",  nativeDisplayName = "简体中文", bcp47Tag = "zh-Hans", group = LanguageGroup.INTERNATIONAL, isSupported = false),
    CHINESE_TRADITIONAL(displayName = "Chinese (Traditional)", nativeDisplayName = "繁體中文", bcp47Tag = "zh-Hant", group = LanguageGroup.INTERNATIONAL, isSupported = false);

    companion object {
        /** Safe deserialisation — returns SYSTEM_DEFAULT on unknown stored value. */
        fun fromName(name: String): AppLanguage =
            entries.firstOrNull { it.name == name } ?: SYSTEM_DEFAULT

        /** National languages in declaration order. */
        fun nationalLanguages(): List<AppLanguage> =
            entries.filter { it.group == LanguageGroup.NATIONAL }

        /** International languages sorted alphabetically by display name. */
        fun internationalLanguages(): List<AppLanguage> =
            entries.filter { it.group == LanguageGroup.INTERNATIONAL }
                .sortedBy { it.displayName }

        /** All currently localized languages. */
        fun supportedLanguages(): List<AppLanguage> =
            entries.filter { it.isSupported }
    }
}

enum class LanguageGroup { SYSTEM, NATIONAL, INTERNATIONAL }
