package cloud.suichuan.app.util

import cloud.suichuan.app.model.TransferPayload

/**
 * Minimal flat string-map JSON writer/parser, written by hand so the codec
 * stays pure Kotlin (no org.json, no Android classes) and can be unit-tested
 * on the JVM.
 *
 * The writer quotes every value as a JSON string. The parser additionally
 * accepts raw JSON numbers and booleans (the backend stores and returns the
 * payload as JSON, where sizeBytes may come back as a number).
 */
object PayloadCodec {

    // ---------- writing ----------

    fun encode(payload: TransferPayload): String = writeMap(payload.toMap())

    fun writeMap(map: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((key, value) in map) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escape(key)).append('"')
            sb.append(':')
            sb.append('"').append(escape(value)).append('"')
        }
        sb.append('}')
        return sb.toString()
    }

    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> {
                    if (c.code < 0x20) {
                        sb.append("\\u")
                        sb.append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    // ---------- parsing ----------

    /** Parses one flat JSON object into a string map. Returns null on bad input. */
    fun parseMap(json: String): Map<String, String>? {
        val parser = Parser(json)
        return try {
            val map = parser.parseObject()
            parser.skipWhitespace()
            if (parser.atEnd()) map else null
        } catch (e: Exception) {
            null
        }
    }

    fun decode(json: String): TransferPayload? {
        val map = parseMap(json) ?: return null
        return TransferPayload.fromMap(map)
    }

    /**
     * Finds a payload JSON object embedded in free text (e.g. a pasted share
     * message) and decodes it. Takes the first '{' and the last '}' so extra
     * lines around the JSON are tolerated.
     */
    fun extractFromText(text: String): TransferPayload? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return decode(text.substring(start, end + 1))
    }

    /** Builds the text the sender shares (and the QR code encodes). */
    fun shareText(payload: TransferPayload, pickupCode: String?): String {
        val sb = StringBuilder()
        sb.append("随传：我用「随传」给你发了一个应用「").append(payload.appName).append("」。\n")
        if (!pickupCode.isNullOrBlank()) {
            // The code comes first and prominently: the receiver can type it,
            // or paste this whole message and the app finds the code in it.
            sb.append("取件码：").append(pickupCode).append("\n")
            sb.append("打开「随传」输入这个取件码就能接收；也可以把这整段文字粘贴到随传里。\n")
        }
        if (payload.downloadUrl.isNotBlank()) {
            sb.append("在随传里粘贴这整段文字就能接收；也可以直接打开下载链接：\n")
            sb.append(payload.downloadUrl).append('\n')
        } else if (pickupCode.isNullOrBlank()) {
            sb.append("在随传里粘贴这整段文字就能接收。\n")
        }
        sb.append(encode(payload))
        return sb.toString()
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun parseObject(): Map<String, String> {
            skipWhitespace()
            expect('{')
            val map = LinkedHashMap<String, String>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = if (peek() == '"') {
                    parseString()
                } else {
                    parseLiteral()
                }
                map[key] = value
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw IllegalArgumentException("unexpected character in object")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (index < text.length) {
                val c = text[index++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (index >= text.length) throw IllegalArgumentException("bad escape")
                        when (val e = text[index++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw IllegalArgumentException("bad unicode escape")
                                }
                                val hex = text.substring(index, index + 4)
                                index += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape: $e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }

        /** Reads a raw number/true/false/null token and returns it as text. */
        private fun parseLiteral(): String {
            val start = index
            while (index < text.length && text[index] !in ",}] \t\r\n") index++
            if (index == start) throw IllegalArgumentException("missing value")
            return text.substring(start, index)
        }

        private fun peek(): Char {
            if (index >= text.length) throw IllegalArgumentException("unexpected end")
            return text[index]
        }

        private fun next(): Char {
            if (index >= text.length) throw IllegalArgumentException("unexpected end")
            return text[index++]
        }

        private fun expect(c: Char) {
            if (next() != c) throw IllegalArgumentException("expected $c")
        }
    }
}
