package com.github.roarappstudio.btkontroller

/**
 * The in-app language options.
 *
 * Names are written in the language itself rather than translated, so the list stays
 * readable no matter which language is currently active -- the usual convention for a
 * language picker.
 *
 * Selection is applied through the platform's per-app language support
 * (`LocaleManager`, API 33+). On older versions the app follows the system locale and the
 * picker is hidden.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", "System default"),
    EN("en", "English"),
    ZH_CN("zh-CN", "中文（简体）"),
    ZH_TW("zh-TW", "中文（繁體）"),
    HI("hi", "हिन्दी"),
    ES("es", "Español"),
    FR("fr", "Français"),
    AR("ar", "العربية"),
    BN("bn", "বাংলা"),
    PT_BR("pt-BR", "Português (Brasil)"),
    RU("ru", "Русский"),
    ID("id", "Bahasa Indonesia"),
    JA("ja", "日本語"),
    DE("de", "Deutsch"),
    KO("ko", "한국어"),
    TR("tr", "Türkçe"),
    IT("it", "Italiano"),
    VI("vi", "Tiếng Việt"),
    PL("pl", "Polski"),
    TH("th", "ไทย"),
    UK("uk", "Українська"),
    NL("nl", "Nederlands");

    companion object {
        fun from(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag == tag } ?: SYSTEM
    }
}
