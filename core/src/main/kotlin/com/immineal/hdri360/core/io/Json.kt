package com.immineal.hdri360.core.io

import java.util.LinkedHashMap
import java.util.Locale

/**
 * A very small JSON writer and parser, enough for the capture bundle's sidecar
 * metadata. Insertion order is preserved so the file diffs cleanly between runs.
 */
object Json {

    interface Value {
        fun asString(): String = throw IllegalStateException("not a string")
        fun asDouble(): Double = throw IllegalStateException("not a number")
        fun asBoolean(): Boolean = throw IllegalStateException("not a boolean")
        operator fun get(key: String): Value = throw IllegalStateException("not an object")
        fun at(index: Int): Value = throw IllegalStateException("not an array")
        fun size(): Int = throw IllegalStateException("not a container")
    }

    class Str internal constructor(@JvmField internal val v: String) : Value {
        override fun asString(): String = v
        override fun toString(): String = quote(v)
    }

    class Num internal constructor(@JvmField internal val v: Double) : Value {
        override fun asDouble(): Double = v
        override fun toString(): String {
            if (v == Math.rint(v) && Math.abs(v) < 1e15) return v.toLong().toString()
            // Double.toString gives the shortest text that reads back bit-identical;
            // a fixed %g would silently truncate exposure times like 1/15 s.
            return v.toString()
        }
    }

    class Bool internal constructor(@JvmField internal val v: Boolean) : Value {
        override fun asBoolean(): Boolean = v
        override fun toString(): String = v.toString()
    }

    class Null : Value {
        override fun toString(): String = "null"
    }

    class Obj : Value {
        @JvmField internal val fields: MutableMap<String, Value> = LinkedHashMap()

        fun put(key: String, v: String): Obj { fields[key] = Str(v); return this }
        fun put(key: String, v: Double): Obj { fields[key] = Num(v); return this }
        fun put(key: String, v: Long): Obj { fields[key] = Num(v.toDouble()); return this }
        fun put(key: String, v: Boolean): Obj { fields[key] = Bool(v); return this }
        fun put(key: String, v: Value): Obj { fields[key] = v; return this }

        fun put(key: String, values: DoubleArray): Obj {
            val a = Arr()
            for (d in values) a.add(d)
            fields[key] = a
            return this
        }

        override operator fun get(key: String): Value =
            fields[key] ?: throw IllegalArgumentException("no such field: $key")

        fun has(key: String): Boolean = fields.containsKey(key)
        override fun size(): Int = fields.size

        override fun toString(): String {
            val b = StringBuilder("{")
            var firstEntry = true
            for (e in fields.entries) {
                if (!firstEntry) b.append(',')
                firstEntry = false
                b.append(quote(e.key)).append(':').append(e.value)
            }
            return b.append('}').toString()
        }
    }

    class Arr : Value {
        @JvmField internal val items: MutableList<Value> = ArrayList()

        fun add(v: Value): Arr { items.add(v); return this }
        fun add(v: Double): Arr { items.add(Num(v)); return this }
        fun add(v: String): Arr { items.add(Str(v)); return this }

        override fun at(i: Int): Value = items[i]
        override fun size(): Int = items.size

        override fun toString(): String {
            val b = StringBuilder("[")
            for (i in items.indices) {
                if (i > 0) b.append(',')
                b.append(items[i])
            }
            return b.append(']').toString()
        }
    }

    @JvmStatic
    internal fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (i in s.indices) {
            val c = s[i]
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else ->
                    if (c.code < 0x20) b.append(String.format(Locale.US, "\\u%04x", c.code))
                    else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    @JvmStatic
    fun parse(text: String): Value {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw IllegalArgumentException("trailing content at " + p.pos)
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && Character.isWhitespace(s[pos])) pos++
        }

        fun peek(): Char {
            if (atEnd()) throw IllegalArgumentException("unexpected end of JSON")
            return s[pos]
        }

        fun expect(c: Char) {
            skipWhitespace()
            if (atEnd() || s[pos] != c)
                throw IllegalArgumentException("expected '" + c + "' at " + pos)
            pos++
        }

        fun parseValue(): Value {
            skipWhitespace()
            val c = peek()
            if (c == '{') return parseObject()
            if (c == '[') return parseArray()
            if (c == '"') return Str(parseString())
            if (s.startsWith("true", pos)) { pos += 4; return Bool(true) }
            if (s.startsWith("false", pos)) { pos += 5; return Bool(false) }
            if (s.startsWith("null", pos)) { pos += 4; return Null() }
            return Num(parseNumber())
        }

        fun parseObject(): Obj {
            val o = Obj()
            expect('{')
            skipWhitespace()
            if (peek() == '}') { pos++; return o }
            while (true) {
                skipWhitespace()
                val key = parseString()
                expect(':')
                o.fields[key] = parseValue()
                skipWhitespace()
                val c = peek()
                if (c == ',') { pos++; continue }
                if (c == '}') { pos++; return o }
                throw IllegalArgumentException("expected ',' or '}' at $pos")
            }
        }

        fun parseArray(): Arr {
            val a = Arr()
            expect('[')
            skipWhitespace()
            if (peek() == ']') { pos++; return a }
            while (true) {
                a.items.add(parseValue())
                skipWhitespace()
                val c = peek()
                if (c == ',') { pos++; continue }
                if (c == ']') { pos++; return a }
                throw IllegalArgumentException("expected ',' or ']' at $pos")
            }
        }

        fun parseString(): String {
            skipWhitespace()
            if (peek() != '"') throw IllegalArgumentException("expected a string at $pos")
            pos++
            val b = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                val c = s[pos++]
                if (c == '"') return b.toString()
                if (c != '\\') { b.append(c); continue }
                when (s[pos++]) {
                    '"' -> b.append('"')
                    '\\' -> b.append('\\')
                    '/' -> b.append('/')
                    'b' -> b.append('\b')
                    'f' -> b.append('\u000C')
                    'n' -> b.append('\n')
                    'r' -> b.append('\r')
                    't' -> b.append('\t')
                    'u' -> {
                        b.append(Integer.parseInt(s.substring(pos, pos + 4), 16).toChar())
                        pos += 4
                    }
                    else -> throw IllegalArgumentException("bad escape at $pos")
                }
            }
        }

        fun parseNumber(): Double {
            val start = pos
            if (!atEnd() && (peek() == '-' || peek() == '+')) pos++
            while (!atEnd() && (Character.isDigit(peek()) || peek() == '.' ||
                    peek() == 'e' || peek() == 'E' || peek() == '-' || peek() == '+')) pos++
            if (pos == start) throw IllegalArgumentException("expected a value at $start")
            try {
                return s.substring(start, pos).toDouble()
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("bad number at $start", e)
            }
        }
    }
}
