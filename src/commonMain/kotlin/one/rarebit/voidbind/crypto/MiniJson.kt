package one.rarebit.voidbind.crypto

/**
 * Tiny, dependency-free JSON codec for the *flat* cert payload object
 * (string and integer fields only). Deliberately not a general JSON library.
 *
 * The encoder emits **compact** JSON (no insignificant whitespace) with fields
 * in insertion order, matching Go's `encoding/json` `Marshal` of a struct
 * (which emits fields in declaration order). Because the enrolment cert signs
 * the exact payload bytes, this byte layout is part of the wire contract:
 * fields must be listed in the same order voidbind-go declares them
 * (`v, usr, dev, denc, iat, exp`).
 */
object MiniJson {

    /** Encode an ordered list of (key, value) pairs. Values are String or Long/Int. */
    fun encodeObject(fields: List<Pair<String, Any>>): String {
        val sb = StringBuilder()
        sb.append('{')
        for ((i, field) in fields.withIndex()) {
            if (i > 0) sb.append(',')
            encodeString(sb, field.first)
            sb.append(':')
            when (val v = field.second) {
                is String -> encodeString(sb, v)
                is Int -> sb.append(v.toString())
                is Long -> sb.append(v.toString())
                else -> throw IllegalArgumentException("unsupported JSON value type: ${v::class}")
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun encodeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u")
                    val hex = c.code.toString(16)
                    repeat(4 - hex.length) { sb.append('0') }
                    sb.append(hex)
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    /**
     * A parsed object. Values are [String], [Long], or — for a JSON array of
     * integers, e.g. weblogin v2's `candidates` — a `List<Long>`. Objects nested as
     * values are still out of scope (this stays a flat, wire-shaped codec); only the
     * top level and integer arrays are supported.
     */
    fun parseObject(json: String): Map<String, Any> {
        val p = Parser(json)
        p.skipWs()
        p.expect('{')
        val out = LinkedHashMap<String, Any>()
        p.skipWs()
        if (p.peek() == '}') { p.next(); return out }
        while (true) {
            p.skipWs()
            val key = p.parseString()
            p.skipWs()
            p.expect(':')
            p.skipWs()
            out[key] = p.parseValue()
            p.skipWs()
            when (val c = p.next()) {
                ',' -> continue
                '}' -> break
                else -> throw IllegalArgumentException("expected ',' or '}', got '$c'")
            }
        }
        return out
    }

    private class Parser(val s: String) {
        var i = 0

        fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("unexpected end of JSON")
        fun next(): Char = peek().also { i++ }
        fun expect(c: Char) { val n = next(); require(n == c) { "expected '$c', got '$n'" } }

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun parseValue(): Any = when (val c = peek()) {
            '"' -> parseString()
            '[' -> parseIntArray()
            else -> if (c == '-' || c in '0'..'9') parseNumber()
            else throw IllegalArgumentException("unsupported JSON value starting with '$c'")
        }

        /** Parse a JSON array of integers to a `List<Long>` (weblogin v2 `candidates`). */
        fun parseIntArray(): List<Long> {
            expect('[')
            val out = ArrayList<Long>()
            skipWs()
            if (peek() == ']') { next(); return out }
            while (true) {
                skipWs()
                out.add(parseNumber())
                skipWs()
                when (val c = next()) {
                    ',' -> continue
                    ']' -> break
                    else -> throw IllegalArgumentException("expected ',' or ']', got '$c'")
                }
            }
            return out
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                when (val c = next()) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            val hex = s.substring(i, i + 4)
                            i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("bad escape '\\$e'")
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseNumber(): Long {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            // This codec only carries integer fields; reject fractions/exponents.
            require(i > start) { "invalid number" }
            return s.substring(start, i).toLong()
        }
    }
}
