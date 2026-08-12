package io.github.kunelab.kontroller

/**
 * One physical key press: a HID usage from the Keyboard/Keypad page plus the modifiers the
 * host needs to see. Usage codes are named after their US-QWERTY legend because that is
 * simply how the HID spec names *positions*.
 */
data class KeyStroke(
    val usage: Int,
    val shift: Boolean = false,
    val altGr: Boolean = false
)

/**
 * The keyboard layout of the **host**, not of the phone.
 *
 * Bluetooth HID transmits key *positions*, never characters -- the host decides which
 * character a position produces by applying its own layout. So typing "m" sends the usage
 * for the key that a US keyboard labels M (0x10), and a host set to French AZERTY prints
 * "," because that is what sits at that position on AZERTY.
 *
 * ### Why there is no Chinese, Korean, Hindi or Arabic entry
 *
 * Those scripts are not produced by a keyboard layout at all: the host runs an input method
 * that converts Latin keystrokes (pinyin, romaji, transliteration) or its own mapping into
 * the target script. The underlying physical layout is US QWERTY, so [US] is the correct
 * choice for China, India, Korea, Vietnam, Thailand, Israel and the Arabic-speaking world.
 * Japan is the exception, having a genuinely different physical layout ([JP]).
 *
 * Each layout is expressed as the US table plus the positions that differ, which keeps the
 * data small and reviewable. If a symbol comes out wrong on some layout, the fix is a single
 * entry in the relevant `override` list below.
 */
enum class HostLayout(val key: String) {

    /**
     * US QWERTY. No table: the existing Android-keycode to HID-usage path already assumes
     * US positions, so it is used unchanged.
     *
     * Also correct for China, India, Korea, Vietnam, Thailand, Indonesia, the Philippines,
     * Israel, Arabic-speaking countries, Russia (Latin mode), Poland, the Netherlands,
     * Australia and English Canada.
     */
    US("us"),

    /** British QWERTY. */
    UK("uk"),

    /** French AZERTY (standard `fr`, not `fr-oss`). */
    FR("fr"),

    /** Belgian AZERTY -- close to French but not identical. */
    BE("be"),

    /** German QWERTZ. Also the base for Austria. */
    DE("de"),

    /** Swiss German QWERTZ. */
    CH("ch"),

    /** Spanish (Spain). */
    ES("es"),

    /** Latin American Spanish. */
    LATAM("latam"),

    /** Italian. */
    IT("it"),

    /** Portuguese (Portugal). */
    PT("pt"),

    /** Brazilian ABNT2. */
    BR("br"),

    /** Swedish / Finnish. */
    SE("se"),

    /** Danish. */
    DK("dk"),

    /** Norwegian. */
    NO("no"),

    /** Japanese JIS (106/109 key). Japanese text itself is composed by the host IME. */
    JP("jp"),

    /** Turkish Q. */
    TR("tr");

    val strokes: Map<Char, List<KeyStroke>>?
        get() = if (this == US) null else tables[this]

    companion object {
        fun from(key: String?): HostLayout = entries.firstOrNull { it.key == key } ?: US

        // ── HID Keyboard/Keypad usage codes, named by US legend ────────────
        private const val A = 0x04; private const val B = 0x05
        private const val C = 0x06; private const val D = 0x07
        private const val E = 0x08; private const val F = 0x09
        private const val G = 0x0A; private const val H = 0x0B
        private const val I = 0x0C; private const val J = 0x0D
        private const val K = 0x0E; private const val L = 0x0F
        private const val M = 0x10; private const val N = 0x11
        private const val O = 0x12; private const val P = 0x13
        private const val Q = 0x14; private const val R = 0x15
        private const val S = 0x16; private const val T = 0x17
        private const val U = 0x18; private const val V = 0x19
        private const val W = 0x1A; private const val X = 0x1B
        private const val Y = 0x1C; private const val Z = 0x1D

        private const val N1 = 0x1E; private const val N2 = 0x1F
        private const val N3 = 0x20; private const val N4 = 0x21
        private const val N5 = 0x22; private const val N6 = 0x23
        private const val N7 = 0x24; private const val N8 = 0x25
        private const val N9 = 0x26; private const val N0 = 0x27

        private const val SPACE = 0x2C
        private const val MINUS = 0x2D
        private const val EQUAL = 0x2E
        private const val LBRACKET = 0x2F
        private const val RBRACKET = 0x30
        private const val BACKSLASH = 0x31
        private const val SEMICOLON = 0x33
        private const val APOSTROPHE = 0x34
        private const val GRAVE = 0x35
        private const val COMMA = 0x36
        private const val PERIOD = 0x37
        private const val SLASH = 0x38

        /** Extra ISO key between Left Shift and Z, present on European keyboards. */
        private const val ISO_EXTRA = 0x64

        /** International1 -- the extra ABNT2 and JIS key. */
        private const val INTL1 = 0x87

        /** International3 -- the JIS yen key. */
        private const val INTL3 = 0x89

        // ── stroke builders ────────────────────────────────────────────────
        private fun k(usage: Int) = listOf(KeyStroke(usage))
        private fun sh(usage: Int) = listOf(KeyStroke(usage, shift = true))
        private fun gr(usage: Int) = listOf(KeyStroke(usage, altGr = true))
        private fun grsh(usage: Int) =
            listOf(KeyStroke(usage, shift = true, altGr = true))

        /** A dead key followed by space, which is how a bare accent is typed. */
        private fun deadAlone(usage: Int, shift: Boolean = false, altGr: Boolean = false) =
            listOf(KeyStroke(usage, shift, altGr), KeyStroke(SPACE))

        /** A dead key followed by a letter, producing an accented letter. */
        private fun dead(
            deadUsage: Int,
            letterUsage: Int,
            deadShift: Boolean = false,
            deadAltGr: Boolean = false,
            capital: Boolean = false
        ) = listOf(
            KeyStroke(deadUsage, deadShift, deadAltGr),
            KeyStroke(letterUsage, capital)
        )

        /**
         * The US QWERTY table, used directly as the base for every other layout. [US] itself
         * keeps the older keycode path so its already-verified behaviour cannot regress.
         */
        private fun usBase(): MutableMap<Char, List<KeyStroke>> {
            val map = HashMap<Char, List<KeyStroke>>(160)

            val letters = listOf(
                'a' to A, 'b' to B, 'c' to C, 'd' to D, 'e' to E, 'f' to F, 'g' to G,
                'h' to H, 'i' to I, 'j' to J, 'k' to K, 'l' to L, 'm' to M, 'n' to N,
                'o' to O, 'p' to P, 'q' to Q, 'r' to R, 's' to S, 't' to T, 'u' to U,
                'v' to V, 'w' to W, 'x' to X, 'y' to Y, 'z' to Z
            )
            for ((ch, usage) in letters) {
                map[ch] = k(usage)
                map[ch.uppercaseChar()] = sh(usage)
            }

            val digits = listOf(
                '1' to N1, '2' to N2, '3' to N3, '4' to N4, '5' to N5,
                '6' to N6, '7' to N7, '8' to N8, '9' to N9, '0' to N0
            )
            for ((ch, usage) in digits) map[ch] = k(usage)

            map['!'] = sh(N1); map['@'] = sh(N2); map['#'] = sh(N3)
            map['$'] = sh(N4); map['%'] = sh(N5); map['^'] = sh(N6)
            map['&'] = sh(N7); map['*'] = sh(N8); map['('] = sh(N9)
            map[')'] = sh(N0)

            map['-'] = k(MINUS);      map['_'] = sh(MINUS)
            map['='] = k(EQUAL);      map['+'] = sh(EQUAL)
            map['['] = k(LBRACKET);   map['{'] = sh(LBRACKET)
            map[']'] = k(RBRACKET);   map['}'] = sh(RBRACKET)
            map['\\'] = k(BACKSLASH); map['|'] = sh(BACKSLASH)
            map[';'] = k(SEMICOLON);  map[':'] = sh(SEMICOLON)
            map['\''] = k(APOSTROPHE); map['"'] = sh(APOSTROPHE)
            map['`'] = k(GRAVE);      map['~'] = sh(GRAVE)
            map[','] = k(COMMA);      map['<'] = sh(COMMA)
            map['.'] = k(PERIOD);     map['>'] = sh(PERIOD)
            map['/'] = k(SLASH);      map['?'] = sh(SLASH)
            map[' '] = k(SPACE)

            return map
        }

        /** US base with the listed positions replaced. */
        private fun variant(
            swapLetters: List<Pair<Char, Int>> = emptyList(),
            overrides: Map<Char, List<KeyStroke>> = emptyMap()
        ): Map<Char, List<KeyStroke>> {
            val map = usBase()
            for ((ch, usage) in swapLetters) {
                map[ch] = k(usage)
                map[ch.uppercaseChar()] = sh(usage)
            }
            map.putAll(overrides)
            return map
        }

        private val tables: Map<HostLayout, Map<Char, List<KeyStroke>>> by lazy {
            mapOf(
                UK to uk(),
                FR to fr(),
                BE to be(),
                DE to de(),
                CH to ch(),
                ES to es(),
                LATAM to latam(),
                IT to it(),
                PT to pt(),
                BR to br(),
                SE to se(),
                DK to dk(),
                NO to no(),
                JP to jp(),
                TR to tr()
            )
        }

        // ── British ────────────────────────────────────────────────────────
        private fun uk() = variant(
            overrides = mapOf(
                '"' to sh(N2),
                '@' to sh(APOSTROPHE),
                '£' to sh(N3),
                '#' to k(BACKSLASH),
                '~' to sh(BACKSLASH),
                '\\' to k(ISO_EXTRA),
                '|' to sh(ISO_EXTRA),
                '¬' to sh(GRAVE),
                '€' to gr(N4)
            )
        )

        // ── French AZERTY ──────────────────────────────────────────────────
        private fun fr() = variant(
            swapLetters = listOf('a' to Q, 'q' to A, 'z' to W, 'w' to Z, 'm' to SEMICOLON),
            overrides = mapOf(
                // Digits need Shift; their bare positions carry punctuation and accents.
                '1' to sh(N1), '2' to sh(N2), '3' to sh(N3), '4' to sh(N4), '5' to sh(N5),
                '6' to sh(N6), '7' to sh(N7), '8' to sh(N8), '9' to sh(N9), '0' to sh(N0),
                '&' to k(N1), 'é' to k(N2), '"' to k(N3), '\'' to k(N4), '(' to k(N5),
                '-' to k(N6), 'è' to k(N7), '_' to k(N8), 'ç' to k(N9), 'à' to k(N0),
                ')' to k(MINUS), '°' to sh(MINUS),
                '=' to k(EQUAL), '+' to sh(EQUAL),
                '~' to deadAlone(N2, altGr = true),
                '#' to gr(N3), '{' to gr(N4), '[' to gr(N5), '|' to gr(N6),
                '`' to deadAlone(N7, altGr = true),
                '\\' to gr(N8), '@' to gr(N0), ']' to gr(MINUS), '}' to gr(EQUAL),
                '$' to k(RBRACKET), '£' to sh(RBRACKET), '¤' to gr(RBRACKET),
                'ù' to k(APOSTROPHE), '%' to sh(APOSTROPHE),
                '*' to k(BACKSLASH), 'µ' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ',' to k(M), '?' to sh(M),
                ';' to k(COMMA), '.' to sh(COMMA),
                ':' to k(PERIOD), '/' to sh(PERIOD),
                '!' to k(SLASH), '§' to sh(SLASH),
                '€' to gr(E),
                '^' to deadAlone(LBRACKET), '¨' to deadAlone(LBRACKET, shift = true),
                'â' to dead(LBRACKET, Q), 'ê' to dead(LBRACKET, E),
                'î' to dead(LBRACKET, I), 'ô' to dead(LBRACKET, O),
                'û' to dead(LBRACKET, U),
                'ä' to dead(LBRACKET, Q, deadShift = true),
                'ë' to dead(LBRACKET, E, deadShift = true),
                'ï' to dead(LBRACKET, I, deadShift = true),
                'ö' to dead(LBRACKET, O, deadShift = true),
                'ü' to dead(LBRACKET, U, deadShift = true)
            )
        )

        // ── Belgian AZERTY ─────────────────────────────────────────────────
        private fun be() = variant(
            swapLetters = listOf('a' to Q, 'q' to A, 'z' to W, 'w' to Z, 'm' to SEMICOLON),
            overrides = mapOf(
                '1' to sh(N1), '2' to sh(N2), '3' to sh(N3), '4' to sh(N4), '5' to sh(N5),
                '6' to sh(N6), '7' to sh(N7), '8' to sh(N8), '9' to sh(N9), '0' to sh(N0),
                '&' to k(N1), 'é' to k(N2), '"' to k(N3), '\'' to k(N4), '(' to k(N5),
                '§' to k(N6), 'è' to k(N7), '!' to k(N8), 'ç' to k(N9), 'à' to k(N0),
                ')' to k(MINUS), '°' to sh(MINUS),
                '-' to k(EQUAL), '_' to sh(EQUAL),
                '$' to k(RBRACKET), '*' to sh(RBRACKET),
                'ù' to k(APOSTROPHE), '%' to sh(APOSTROPHE),
                'µ' to k(BACKSLASH), '£' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ',' to k(M), '?' to sh(M),
                ';' to k(COMMA), '.' to sh(COMMA),
                ':' to k(PERIOD), '/' to sh(PERIOD),
                '=' to k(SLASH), '+' to sh(SLASH),
                '|' to gr(N1), '@' to gr(N2), '#' to gr(N3),
                '{' to gr(N9), '}' to gr(N0),
                '[' to gr(LBRACKET), ']' to gr(RBRACKET),
                '\\' to gr(ISO_EXTRA), '€' to gr(E),
                '~' to deadAlone(RBRACKET, altGr = true),
                '^' to deadAlone(LBRACKET), '¨' to deadAlone(LBRACKET, shift = true),
                'â' to dead(LBRACKET, Q), 'ê' to dead(LBRACKET, E),
                'î' to dead(LBRACKET, I), 'ô' to dead(LBRACKET, O),
                'û' to dead(LBRACKET, U),
                'ë' to dead(LBRACKET, E, deadShift = true),
                'ï' to dead(LBRACKET, I, deadShift = true),
                'ü' to dead(LBRACKET, U, deadShift = true)
            )
        )

        // ── German QWERTZ ──────────────────────────────────────────────────
        private fun de() = variant(
            swapLetters = listOf('z' to Y, 'y' to Z),
            overrides = mapOf(
                '"' to sh(N2), '§' to sh(N3), '&' to sh(N6), '/' to sh(N7),
                '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                'ß' to k(MINUS), '?' to sh(MINUS),
                'ü' to k(LBRACKET), 'Ü' to sh(LBRACKET),
                '+' to k(RBRACKET), '*' to sh(RBRACKET),
                'ö' to k(SEMICOLON), 'Ö' to sh(SEMICOLON),
                'ä' to k(APOSTROPHE), 'Ä' to sh(APOSTROPHE),
                '#' to k(BACKSLASH), '\'' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '^' to deadAlone(GRAVE), '°' to sh(GRAVE),
                '@' to gr(Q), '€' to gr(E),
                '{' to gr(N7), '[' to gr(N8), ']' to gr(N9), '}' to gr(N0),
                '\\' to gr(MINUS), '~' to gr(RBRACKET), '|' to gr(ISO_EXTRA),
                'µ' to gr(M),
                '´' to deadAlone(EQUAL), '`' to deadAlone(EQUAL, shift = true),
                'é' to dead(EQUAL, E), 'è' to dead(EQUAL, E, deadShift = true)
            )
        )

        // ── Swiss German QWERTZ ────────────────────────────────────────────
        private fun ch() = variant(
            swapLetters = listOf('z' to Y, 'y' to Z),
            overrides = mapOf(
                '+' to sh(N1), '"' to sh(N2), '*' to sh(N3), 'ç' to sh(N4),
                '%' to sh(N5), '&' to sh(N6), '/' to sh(N7), '(' to sh(N8),
                ')' to sh(N9), '=' to sh(N0),
                '\'' to k(MINUS), '?' to sh(MINUS),
                '^' to deadAlone(EQUAL), '`' to deadAlone(EQUAL, shift = true),
                'ü' to k(LBRACKET), 'è' to sh(LBRACKET),
                '¨' to deadAlone(RBRACKET),
                'ö' to k(SEMICOLON), 'é' to sh(SEMICOLON),
                'ä' to k(APOSTROPHE), 'à' to sh(APOSTROPHE),
                '$' to k(BACKSLASH), '£' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '@' to gr(N2), '#' to gr(N3), '€' to gr(E),
                '|' to gr(N7), '\\' to gr(ISO_EXTRA),
                '[' to gr(LBRACKET), ']' to gr(RBRACKET),
                '{' to gr(APOSTROPHE), '}' to gr(BACKSLASH),
                '~' to deadAlone(RBRACKET, altGr = true)
            )
        )

        // ── Spanish (Spain) ────────────────────────────────────────────────
        private fun es() = variant(
            overrides = mapOf(
                '"' to sh(N2), '·' to sh(N3), '&' to sh(N6), '/' to sh(N7),
                '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                '\'' to k(MINUS), '?' to sh(MINUS),
                '¡' to k(EQUAL), '¿' to sh(EQUAL),
                '`' to deadAlone(LBRACKET), '^' to deadAlone(LBRACKET, shift = true),
                '+' to k(RBRACKET), '*' to sh(RBRACKET),
                'ñ' to k(SEMICOLON), 'Ñ' to sh(SEMICOLON),
                '´' to deadAlone(APOSTROPHE), '¨' to deadAlone(APOSTROPHE, shift = true),
                'ç' to k(BACKSLASH), 'Ç' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                'º' to k(GRAVE), 'ª' to sh(GRAVE),
                '\\' to gr(GRAVE), '|' to gr(N1), '@' to gr(N2), '#' to gr(N3),
                '~' to gr(N4), '€' to gr(E),
                '[' to gr(LBRACKET), ']' to gr(RBRACKET),
                '{' to gr(APOSTROPHE), '}' to gr(BACKSLASH),
                'á' to dead(APOSTROPHE, A), 'é' to dead(APOSTROPHE, E),
                'í' to dead(APOSTROPHE, I), 'ó' to dead(APOSTROPHE, O),
                'ú' to dead(APOSTROPHE, U),
                'ü' to dead(APOSTROPHE, U, deadShift = true)
            )
        )

        // ── Latin American Spanish ─────────────────────────────────────────
        private fun latam() = variant(
            overrides = mapOf(
                '"' to sh(N2), '#' to sh(N3), '&' to sh(N6), '/' to sh(N7),
                '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                '\'' to k(MINUS), '?' to sh(MINUS),
                '¿' to k(EQUAL), '¡' to sh(EQUAL),
                '´' to deadAlone(LBRACKET), '¨' to deadAlone(LBRACKET, shift = true),
                '+' to k(RBRACKET), '*' to sh(RBRACKET),
                'ñ' to k(SEMICOLON), 'Ñ' to sh(SEMICOLON),
                '{' to k(APOSTROPHE), '[' to sh(APOSTROPHE),
                '}' to k(BACKSLASH), ']' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '|' to k(GRAVE), '°' to sh(GRAVE), '¬' to gr(GRAVE),
                '@' to gr(Q), '\\' to gr(MINUS), '~' to gr(LBRACKET),
                '€' to gr(E),
                'á' to dead(LBRACKET, A), 'é' to dead(LBRACKET, E),
                'í' to dead(LBRACKET, I), 'ó' to dead(LBRACKET, O),
                'ú' to dead(LBRACKET, U),
                'ü' to dead(LBRACKET, U, deadShift = true)
            )
        )

        // ── Italian ────────────────────────────────────────────────────────
        private fun it() = variant(
            overrides = mapOf(
                '"' to sh(N2), '£' to sh(N3), '&' to sh(N6), '/' to sh(N7),
                '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                '\'' to k(MINUS), '?' to sh(MINUS),
                'ì' to k(EQUAL), '^' to sh(EQUAL),
                'è' to k(LBRACKET), 'é' to sh(LBRACKET),
                '+' to k(RBRACKET), '*' to sh(RBRACKET),
                'ò' to k(SEMICOLON), 'ç' to sh(SEMICOLON),
                'à' to k(APOSTROPHE), '°' to sh(APOSTROPHE),
                'ù' to k(BACKSLASH), '§' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '\\' to k(GRAVE), '|' to sh(GRAVE),
                '@' to gr(SEMICOLON), '#' to gr(APOSTROPHE),
                '[' to gr(LBRACKET), ']' to gr(RBRACKET),
                '{' to grsh(LBRACKET), '}' to grsh(RBRACKET),
                '€' to gr(E), '~' to grsh(BACKSLASH)
            )
        )

        // ── Portuguese (Portugal) ──────────────────────────────────────────
        private fun pt() = variant(
            overrides = mapOf(
                '"' to sh(N2), '#' to sh(N3), '&' to sh(N6), '/' to sh(N7),
                '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                '\'' to k(MINUS), '?' to sh(MINUS),
                '«' to k(EQUAL), '»' to sh(EQUAL),
                '+' to k(LBRACKET), '*' to sh(LBRACKET),
                '´' to deadAlone(RBRACKET), '`' to deadAlone(RBRACKET, shift = true),
                'ç' to k(SEMICOLON), 'Ç' to sh(SEMICOLON),
                'º' to k(APOSTROPHE), 'ª' to sh(APOSTROPHE),
                '~' to deadAlone(BACKSLASH), '^' to deadAlone(BACKSLASH, shift = true),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '\\' to k(GRAVE), '|' to sh(GRAVE),
                '@' to gr(N2), '£' to gr(N3), '§' to gr(N4),
                '{' to gr(N7), '[' to gr(N8), ']' to gr(N9), '}' to gr(N0),
                '€' to gr(E),
                'á' to dead(RBRACKET, A), 'é' to dead(RBRACKET, E),
                'í' to dead(RBRACKET, I), 'ó' to dead(RBRACKET, O),
                'ú' to dead(RBRACKET, U),
                'ã' to dead(BACKSLASH, A), 'õ' to dead(BACKSLASH, O),
                'â' to dead(BACKSLASH, A, deadShift = true),
                'ê' to dead(BACKSLASH, E, deadShift = true),
                'ô' to dead(BACKSLASH, O, deadShift = true),
                'à' to dead(RBRACKET, A, deadShift = true)
            )
        )

        // ── Brazilian ABNT2 ────────────────────────────────────────────────
        private fun br() = variant(
            overrides = mapOf(
                '¨' to deadAlone(N6, shift = true),
                '&' to sh(N7), '*' to sh(N8), '(' to sh(N9), ')' to sh(N0),
                '´' to deadAlone(LBRACKET), '`' to deadAlone(LBRACKET, shift = true),
                '[' to k(RBRACKET), '{' to sh(RBRACKET),
                'ç' to k(SEMICOLON), 'Ç' to sh(SEMICOLON),
                '~' to deadAlone(APOSTROPHE), '^' to deadAlone(APOSTROPHE, shift = true),
                ']' to k(BACKSLASH), '}' to sh(BACKSLASH),
                '\\' to k(ISO_EXTRA), '|' to sh(ISO_EXTRA),
                ';' to k(SLASH), ':' to sh(SLASH),
                '/' to k(INTL1), '?' to sh(INTL1), '°' to gr(INTL1),
                '\'' to k(GRAVE), '"' to sh(GRAVE),
                '€' to gr(E), '₢' to gr(C),
                'á' to dead(LBRACKET, A), 'é' to dead(LBRACKET, E),
                'í' to dead(LBRACKET, I), 'ó' to dead(LBRACKET, O),
                'ú' to dead(LBRACKET, U),
                'ã' to dead(APOSTROPHE, A), 'õ' to dead(APOSTROPHE, O),
                'â' to dead(APOSTROPHE, A, deadShift = true),
                'ê' to dead(APOSTROPHE, E, deadShift = true),
                'ô' to dead(APOSTROPHE, O, deadShift = true),
                'à' to dead(LBRACKET, A, deadShift = true)
            )
        )

        // ── Swedish / Finnish ──────────────────────────────────────────────
        private fun se() = variant(
            overrides = mapOf(
                '"' to sh(N2), '#' to sh(N3), '¤' to sh(N4), '&' to sh(N6),
                '/' to sh(N7), '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                '+' to k(MINUS), '?' to sh(MINUS),
                '´' to deadAlone(EQUAL), '`' to deadAlone(EQUAL, shift = true),
                'å' to k(LBRACKET), 'Å' to sh(LBRACKET),
                '¨' to deadAlone(RBRACKET), '^' to deadAlone(RBRACKET, shift = true),
                'ö' to k(SEMICOLON), 'Ö' to sh(SEMICOLON),
                'ä' to k(APOSTROPHE), 'Ä' to sh(APOSTROPHE),
                '\'' to k(BACKSLASH), '*' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '§' to k(GRAVE), '½' to sh(GRAVE),
                '@' to gr(N2), '£' to gr(N3), '$' to gr(N4),
                '{' to gr(N7), '[' to gr(N8), ']' to gr(N9), '}' to gr(N0),
                '\\' to gr(MINUS), '~' to deadAlone(RBRACKET, altGr = true),
                '|' to gr(ISO_EXTRA), '€' to gr(E)
            )
        )

        // ── Danish ─────────────────────────────────────────────────────────
        private fun dk() = variant(
            overrides = mapOf(
                '"' to sh(N2), '#' to sh(N3), '¤' to sh(N4), '&' to sh(N6),
                '/' to sh(N7), '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                '+' to k(MINUS), '?' to sh(MINUS),
                '´' to deadAlone(EQUAL), '`' to deadAlone(EQUAL, shift = true),
                'å' to k(LBRACKET), 'Å' to sh(LBRACKET),
                '¨' to deadAlone(RBRACKET), '^' to deadAlone(RBRACKET, shift = true),
                'æ' to k(SEMICOLON), 'Æ' to sh(SEMICOLON),
                'ø' to k(APOSTROPHE), 'Ø' to sh(APOSTROPHE),
                '\'' to k(BACKSLASH), '*' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '½' to k(GRAVE), '§' to sh(GRAVE),
                '@' to gr(N2), '£' to gr(N3), '$' to gr(N4),
                '{' to gr(N7), '[' to gr(N8), ']' to gr(N9), '}' to gr(N0),
                '\\' to gr(ISO_EXTRA), '|' to gr(EQUAL), '€' to gr(E),
                '~' to deadAlone(RBRACKET, altGr = true)
            )
        )

        // ── Norwegian ──────────────────────────────────────────────────────
        private fun no() = variant(
            overrides = mapOf(
                '"' to sh(N2), '#' to sh(N3), '¤' to sh(N4), '&' to sh(N6),
                '/' to sh(N7), '(' to sh(N8), ')' to sh(N9), '=' to sh(N0),
                '+' to k(MINUS), '?' to sh(MINUS),
                '\\' to k(EQUAL), '`' to deadAlone(EQUAL, shift = true),
                'å' to k(LBRACKET), 'Å' to sh(LBRACKET),
                '¨' to deadAlone(RBRACKET), '^' to deadAlone(RBRACKET, shift = true),
                'ø' to k(SEMICOLON), 'Ø' to sh(SEMICOLON),
                'æ' to k(APOSTROPHE), 'Æ' to sh(APOSTROPHE),
                '\'' to k(BACKSLASH), '*' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                ';' to sh(COMMA), ':' to sh(PERIOD),
                '-' to k(SLASH), '_' to sh(SLASH),
                '|' to k(GRAVE), '§' to sh(GRAVE),
                '@' to gr(N2), '£' to gr(N3), '$' to gr(N4),
                '{' to gr(N7), '[' to gr(N8), ']' to gr(N9), '}' to gr(N0),
                '´' to gr(BACKSLASH), '€' to gr(E),
                '~' to deadAlone(RBRACKET, altGr = true)
            )
        )

        // ── Japanese JIS ───────────────────────────────────────────────────
        private fun jp() = variant(
            overrides = mapOf(
                // The JIS digit row keeps ASCII digits but changes the shifted symbols.
                '"' to sh(N2), '&' to sh(N6), '\'' to sh(N7), '(' to sh(N8),
                ')' to sh(N9),
                // Shift+0 produces nothing on JIS; ')' above already covers the paren.
                '=' to sh(MINUS), '-' to k(MINUS),
                '^' to k(EQUAL), '~' to sh(EQUAL),
                '¥' to k(INTL3), '|' to sh(INTL3),
                '@' to k(LBRACKET), '`' to sh(LBRACKET),
                '[' to k(RBRACKET), '{' to sh(RBRACKET),
                ';' to k(SEMICOLON), '+' to sh(SEMICOLON),
                ':' to k(APOSTROPHE), '*' to sh(APOSTROPHE),
                ']' to k(BACKSLASH), '}' to sh(BACKSLASH),
                '\\' to k(INTL1), '_' to sh(INTL1),
                '<' to sh(COMMA), '>' to sh(PERIOD), '?' to sh(SLASH)
            )
        )

        // ── Turkish Q ──────────────────────────────────────────────────────
        //
        // The shifted digit row is  ! ' ^ + % & / ( ) =  reading from the 1 key, which is
        // where three of these entries come from. `'` was on the Shift+7 position, and the
        // two characters that position displaced -- `/` and `+` -- had no entry of their own,
        // so they fell through to the US base and came out as `.` and `_`: the US slash and
        // equals positions are both reassigned below.
        private fun tr() = variant(
            overrides = mapOf(
                '^' to deadAlone(N3, shift = true),
                '\'' to sh(N2), '+' to sh(N4),
                '&' to sh(N6), '/' to sh(N7), '(' to sh(N8), ')' to sh(N9),
                '=' to sh(N0),
                '*' to k(MINUS), '?' to sh(MINUS),
                '-' to k(EQUAL), '_' to sh(EQUAL),
                'ğ' to k(LBRACKET), 'Ğ' to sh(LBRACKET),
                'ü' to k(RBRACKET), 'Ü' to sh(RBRACKET),
                'ş' to k(SEMICOLON), 'Ş' to sh(SEMICOLON),
                'i' to k(APOSTROPHE), 'İ' to sh(APOSTROPHE),
                'ı' to k(I), 'I' to sh(I),
                ',' to k(BACKSLASH), ';' to sh(BACKSLASH),
                '<' to k(ISO_EXTRA), '>' to sh(ISO_EXTRA),
                'ö' to k(COMMA), 'Ö' to sh(COMMA),
                'ç' to k(PERIOD), 'Ç' to sh(PERIOD),
                '.' to k(SLASH), ':' to sh(SLASH),
                '"' to k(GRAVE), 'é' to sh(GRAVE),
                '@' to gr(Q), '€' to gr(E),
                '{' to gr(N7), '[' to gr(N8), ']' to gr(N9), '}' to gr(N0),
                '\\' to gr(MINUS), '|' to gr(ISO_EXTRA),
                '#' to gr(N3), '$' to gr(N4)
            )
        )
    }
}
