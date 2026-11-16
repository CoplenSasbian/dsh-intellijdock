package cc.futurvo.ideadock.contract

/**
 * A tiny JSON codec, deliberately dependency-free.
 *
 * Everything the plugin exchanges with QuickDock and DSH is small JSON
 * documents, and the alternative - the platform's bundled Gson - is on its way
 * out of the platform. Keeping the codec here means the whole `contract` package
 * compiles and is tested without an IDE runtime, which is the main reason the
 * VS Code version was testable in a plain Node process.
 *
 * Values map to Kotlin as: object -> [Map]<String, Any?>, array -> [List]<Any?>,
 * string -> [String], number -> [Long] or [Double], boolean -> [Boolean],
 * null -> null.
 */
object Json {

    /** Thrown for anything that is not valid JSON; callers turn it into a probe failure. */
    class JsonException(message: String) : Exception(message)

    /* --------------------------------- writing -------------------------------- */

    /** Serialise `value`; maps keep their iteration order, so output is stable. */
    fun stringify(value: Any?): String = StringBuilder(256).also { write(value, it) }.toString()

    private fun write(value: Any?, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(stringLiteral(value))
            is Boolean -> out.append(if (value) "true" else "false")
            is Double -> out.append(number(value))
            is Float -> out.append(number(value.toDouble()))
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    out.append(stringLiteral(key.toString())).append(':')
                    write(item, out)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(item, out)
                }
                out.append(']')
            }
            is Array<*> -> write(value.toList(), out)
            else -> out.append(stringLiteral(value.toString()))
        }
    }

    private fun number(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        val asLong = value.toLong()
        return if (asLong.toDouble() == value) asLong.toString() else value.toString()
    }

    /** A quoted, escaped JSON string literal. */
    fun stringLiteral(value: String): String {
        val out = StringBuilder(value.length + 16)
        out.append('"')
        for (character in value) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    if (character < ' ') {
                        out.append("\\u").append(String.format("%04x", character.code))
                    } else {
                        out.append(character)
                    }
            }
        }
        out.append('"')
        return out.toString()
    }

    /* -------------------------------- reading -------------------------------- */

    /** Parse one JSON document. @throws JsonException when it is malformed. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd()) throw JsonException("trailing characters at ${reader.position}")
        return value
    }

    /** Parse a JSON object; `null` when the text is not an object. */
    fun parseObject(text: String): Map<String, Any?>? {
        val value = try {
            parse(text)
        } catch (error: JsonException) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
    }

    /** Parse a JSON array; `null` when the text is not an array. */
    fun parseArray(text: String): List<Any?>? {
        val value = try {
            parse(text)
        } catch (error: JsonException) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return value as? List<Any?>
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].isWhitespace()) position += 1
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw JsonException("unexpected end of input")
            return when (val character = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else ->
                    if (character == '-' || character in '0'..'9') readNumber()
                    else throw JsonException("unexpected character '$character' at $position")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, position)) throw JsonException("invalid literal at $position")
            position += word.length
            return value
        }

        private fun readObject(): Map<String, Any?> {
            position += 1 // '{'
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position += 1
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("expected a key at $position")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') throw JsonException("expected ':' at $position")
                position += 1
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    '}' -> {
                        position += 1
                        return result
                    }
                    else -> throw JsonException("expected ',' or '}' at $position")
                }
            }
        }

        private fun readArray(): List<Any?> {
            position += 1 // '['
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position += 1
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    ']' -> {
                        position += 1
                        return result
                    }
                    else -> throw JsonException("expected ',' or ']' at $position")
                }
            }
        }

        private fun readString(): String {
            position += 1 // '"'
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("unterminated string")
                val character = text[position]
                position += 1
                when {
                    character == '"' -> return out.toString()
                    character == '\\' -> {
                        if (atEnd()) throw JsonException("unterminated escape")
                        val escaped = text[position]
                        position += 1
                        when (escaped) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (position + 4 > text.length) throw JsonException("bad \\u escape")
                                val hex = text.substring(position, position + 4)
                                position += 4
                                out.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonException("bad escape \\$escaped")
                        }
                    }
                    else -> out.append(character)
                }
            }
        }

        private fun readNumber(): Number {
            val start = position
            if (peek() == '-') position += 1
            while (!atEnd() && text[position] in '0'..'9') position += 1
            var fractional = false
            if (!atEnd() && text[position] == '.') {
                fractional = true
                position += 1
                while (!atEnd() && text[position] in '0'..'9') position += 1
            }
            if (!atEnd() && (text[position] == 'e' || text[position] == 'E')) {
                fractional = true
                position += 1
                if (!atEnd() && (text[position] == '+' || text[position] == '-')) position += 1
                while (!atEnd() && text[position] in '0'..'9') position += 1
            }
            val raw = text.substring(start, position)
            if (raw.isEmpty() || raw == "-") throw JsonException("bad number at $start")
            return if (fractional) raw.toDouble() else raw.toLongOrNull() ?: raw.toDouble()
        }

        private fun peek(): Char {
            if (atEnd()) throw JsonException("unexpected end of input")
            return text[position]
        }
    }

    /* ----------------------------- typed accessors ---------------------------- */

    @Suppress("UNCHECKED_CAST")
    fun obj(map: Map<String, Any?>?, key: String): Map<String, Any?>? = map?.get(key) as? Map<String, Any?>

    fun arr(map: Map<String, Any?>?, key: String): List<Any?>? = map?.get(key) as? List<Any?>

    fun str(map: Map<String, Any?>?, key: String): String? {
        val value = map?.get(key) ?: return null
        return when (value) {
            is String -> value
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

    fun num(map: Map<String, Any?>?, key: String): Long? {
        return when (val value = map?.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Number -> value.toLong()
            is String -> value.trim().toLongOrNull()
            else -> null
        }
    }

    fun bool(map: Map<String, Any?>?, key: String): Boolean? {
        return when (val value = map?.get(key)) {
            is Boolean -> value
            is String -> when (value.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> null
            }
            is Number -> value.toInt() != 0
            else -> null
        }
    }

    fun stringList(map: Map<String, Any?>?, key: String): List<String>? {
        val list = arr(map, key) ?: return null
        return list.mapNotNull { it as? String }
    }

    /** The text of a nested object, already serialised (used for `tools/list`). */
    fun objText(value: Any?): String = stringify(value)
}
