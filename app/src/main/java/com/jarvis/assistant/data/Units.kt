package com.jarvis.assistant.data

import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Arithmetic and unit conversion, entirely on the phone.
 *
 * No network, no AI key, no rounding surprises: "12 percent of 4500", "5 km to miles", "50 c to f"
 * and "what is 240 into 3" all answer instantly and identically offline. An assistant that needs the
 * internet to add two numbers is not an assistant.
 *
 * Pure Kotlin; `UnitsTest` covers the grammar and every conversion family.
 */
object Units {

    data class Outcome(val value: Double, val text: String)

    private const val EPS = 1e-9

    /** Never throws: a sentence that is not arithmetic returns null. */
    fun tryCalculate(raw: String): Outcome? {
        val text = PhraseBook.clean(raw)
            .replace("!", "")
            .ifBlank { return null }

        tryPercentOf(text)?.let { return it }
        tryConvert(text)?.let { return it }
        return tryArithmetic(text)?.let { Outcome(it, format(it)) }
    }

    // ---------------------------------------------------------------------------------------------
    // "12 percent of 4500"
    // ---------------------------------------------------------------------------------------------

    private fun tryPercentOf(text: String): Outcome? {
        val match = Regex("^(\\d+(?:\\.\\d+)?)\\s*(?:%|percent|per cent)\\s*(?:of|ka)\\s*(\\d+(?:\\.\\d+)?)$")
            .find(text) ?: return null
        val pct = match.groupValues[1].toDoubleOrNull() ?: return null
        val base = match.groupValues[2].toDoubleOrNull() ?: return null
        val value = base * pct / 100.0
        return Outcome(value, format(value))
    }

    // ---------------------------------------------------------------------------------------------
    // "5 km to miles", "50 c to f", "2 hours in minutes"
    // ---------------------------------------------------------------------------------------------

    private fun tryConvert(text: String): Outcome? {
        val match = Regex(
            "^(?:convert\\s+)?(-?\\d+(?:\\.\\d+)?)\\s*([a-z/]{1,10})\\s*(?:to|in|into|mein)\\s*([a-z/]{1,10})$",
        ).find(text) ?: return null

        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val from = match.groupValues[2]
        val to = match.groupValues[3]

        val converted = convert(value, from, to) ?: return null
        return Outcome(converted, "${format(converted)} ${to}")
    }

    /** Converts between units of the same family. Temperature is handled separately. */
    fun convert(value: Double, fromRaw: String, toRaw: String): Double? {
        val from = normaliseUnit(fromRaw) ?: return null
        val to = normaliseUnit(toRaw) ?: return null

        if (from == to) return value

        // Temperature: the only family with an offset, so it cannot share the ratio maths.
        val tempFrom = TEMPS[from]
        val tempTo = TEMPS[to]
        if (tempFrom != null && tempTo != null) {
            val celsius = when (tempFrom) {
                "c" -> value
                "f" -> (value - 32.0) * 5.0 / 9.0
                else -> value - 273.15
            }
            return when (tempTo) {
                "c" -> celsius
                "f" -> celsius * 9.0 / 5.0 + 32.0
                else -> celsius + 273.15
            }
        }

        val fromUnit = UNITS[from] ?: return null
        val toUnit = UNITS[to] ?: return null
        if (fromUnit.first != toUnit.first) return null
        return value * fromUnit.second / toUnit.second
    }

    private val TEMPS = mapOf(
        "c" to "c", "celsius" to "c", "centigrade" to "c",
        "f" to "f", "fahrenheit" to "f",
        "k" to "k", "kelvin" to "k",
    )

    /** name → (family, factor to the family's base unit) */
    private val UNITS: Map<String, Pair<String, Double>> = buildMap {
        fun add(family: String, vararg names: Pair<String, Double>) {
            for ((name, factor) in names) put(name, family to factor)
        }
        add(
            "length",
            "mm" to 0.001, "millimetre" to 0.001, "millimeter" to 0.001,
            "cm" to 0.01, "centimetre" to 0.01, "centimeter" to 0.01,
            "m" to 1.0, "metre" to 1.0, "meter" to 1.0, "metres" to 1.0, "meters" to 1.0,
            "km" to 1000.0, "kilometre" to 1000.0, "kilometer" to 1000.0,
            "kilometres" to 1000.0, "kilometers" to 1000.0,
            "inch" to 0.0254, "inches" to 0.0254,
            "ft" to 0.3048, "foot" to 0.3048, "feet" to 0.3048,
            "yard" to 0.9144, "yards" to 0.9144,
            "mile" to 1609.344, "miles" to 1609.344,
        )
        add(
            "mass",
            "mg" to 1e-6, "g" to 0.001, "gram" to 0.001, "grams" to 0.001,
            "kg" to 1.0, "kilo" to 1.0, "kilos" to 1.0, "kilogram" to 1.0, "kilograms" to 1.0,
            "lb" to 0.45359237, "lbs" to 0.45359237, "pound" to 0.45359237, "pounds" to 0.45359237,
            "oz" to 0.028349523, "ounce" to 0.028349523, "ounces" to 0.028349523,
            "ton" to 1000.0, "tonne" to 1000.0,
        )
        add(
            "volume",
            "ml" to 0.001, "millilitre" to 0.001, "milliliter" to 0.001,
            "l" to 1.0, "litre" to 1.0, "liter" to 1.0, "litres" to 1.0, "liters" to 1.0,
            "glass" to 0.25, "cup" to 0.24, "cups" to 0.24,
            "pint" to 0.473176, "gallon" to 3.78541, "gallons" to 3.78541,
        )
        add(
            "time",
            "second" to 1.0, "seconds" to 1.0, "sec" to 1.0, "secs" to 1.0,
            "minute" to 60.0, "minutes" to 60.0, "min" to 60.0, "mins" to 60.0,
            "hour" to 3600.0, "hours" to 3600.0, "hr" to 3600.0, "hrs" to 3600.0,
            "day" to 86400.0, "days" to 86400.0,
            "week" to 604800.0, "weeks" to 604800.0,
        )
        add(
            "data",
            "b" to 1.0, "byte" to 1.0, "bytes" to 1.0,
            "kb" to 1024.0, "mb" to 1024.0 * 1024, "gb" to 1024.0 * 1024 * 1024,
            "tb" to 1024.0 * 1024 * 1024 * 1024,
        )
        add(
            "speed",
            "mps" to 1.0,
            "kmph" to 0.2777778, "kph" to 0.2777778,
            "mph" to 0.44704,
        )
    }

    private fun normaliseUnit(raw: String): String? {
        val key = raw.trim().lowercase(Locale.US).trimEnd('.')
        if (key.isEmpty()) return null
        if (key in TEMPS) return key
        return if (key in UNITS) key else null
    }

    // ---------------------------------------------------------------------------------------------
    // Arithmetic
    // ---------------------------------------------------------------------------------------------

    private fun tryArithmetic(text: String): Double? {
        var expr = text
            .replace("what is", " ")
            .replace("whats", " ")
            .replace("calculate", " ")
            .replace("how much is", " ")
            .replace("kitna hota hai", " ")
            .replace("solve", " ")
            .replace("equals", " ")
            .replace("equal to", " ")
            .replace("plus", "+")
            .replace("add", "+")
            .replace("minus", "-")
            .replace("subtract", "-")
            .replace("times", "*")
            .replace("multiplied by", "*")
            .replace("multiply", "*")
            .replace("into", "*")
            .replace("divided by", "/")
            .replace("divide by", "/")
            .replace("over", "/")
            .replace("x", "*")
            .replace("×", "*")
            .replace("÷", "/")
            .replace("^", "^")
            .replace("percent", "%")
            .replace("per cent", "%")
            .trim()

        // "4500 + 12%" means "add 12 percent of it".
        expr = expr.replace(Regex("%"), " % ")
        expr = expr.replace(Regex("\\s+"), " ").trim()
        if (expr.isEmpty()) return null
        if (!expr.any { it.isDigit() }) return null
        if (!expr.any { it in "+-*/^%" }) return null

        return try {
            val parser = ExpressionParser(expr)
            val value = parser.parse()
            if (value.isNaN() || value.isInfinite()) null else value
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Recursive descent: expression → term → factor → number.
     *
     * `%` is postfix and means "the previous number divided by 100", and it also multiplies
     * implicitly: "200 + 10%" is 220, not 200.1, because that is what people mean.
     */
    private class ExpressionParser(private val input: String) {
        private var pos = 0

        /** Set by [factor] when the value it just produced came from a percentage. */
        private var lastWasPercent = false

        fun parse(): Double {
            val value = expression()
            skipSpaces()
            if (pos < input.length) throw IllegalArgumentException("trailing input")
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                value = when {
                    peek('+') -> {
                        pos++
                        val rhs = term()
                        // "200 + 10%" means "200 plus ten percent of 200".
                        if (lastWasPercent) value + value * rhs else value + rhs
                    }
                    peek('-') -> {
                        pos++
                        val rhs = term()
                        if (lastWasPercent) value - value * rhs else value - rhs
                    }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = factor()
            while (true) {
                skipSpaces()
                value = when {
                    peek('*') -> {
                        pos++
                        value * factor()
                    }
                    peek('/') -> {
                        pos++
                        val divisor = factor()
                        if (abs(divisor) < EPS) throw ArithmeticException("divide by zero")
                        value / divisor
                    }
                    else -> return value
                }
            }
        }

        private fun factor(): Double {
            lastWasPercent = false
            var value = primary()
            skipSpaces()
            if (peek('%')) {
                pos++
                value /= 100.0
                lastWasPercent = true
            }
            return value
        }

        private fun primary(): Double {
            skipSpaces()
            if (peek('(')) {
                pos++
                val value = expression()
                skipSpaces()
                if (peek(')')) pos++
                return value
            }
            if (peek('-')) {
                pos++
                return -primary()
            }
            if (peek('+')) {
                pos++
                return primary()
            }
            return number()
        }

        private fun number(): Double {
            skipSpaces()
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("number expected")
            val raw = input.substring(start, pos)
            val value = raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
            skipSpaces()
            if (pos < input.length && (input[pos] == '^')) {
                pos++
                return value.pow(primary())
            }
            return value
        }

        private fun peek(ch: Char): Boolean = pos < input.length && input[pos] == ch

        private fun skipSpaces() {
            while (pos < input.length && input[pos] == ' ') pos++
        }
    }

    /** Digits with no pointless decimals, and grouping for big numbers. */
    fun format(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return "—"
        val rounded = if (abs(value - value.roundToLong()) < 1e-9) {
            value.roundToLong().toDouble()
        } else {
            (value * 100.0).roundToLong() / 100.0
        }
        val text = if (abs(rounded - rounded.roundToLong()) < 1e-9) {
            rounded.roundToLong().toString()
        } else {
            String.format(Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
        }
        return text
    }
}
