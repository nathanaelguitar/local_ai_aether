package com.nathanaelguitar.canopychat.core

/**
 * Port of AetherResponseNormalizer from iphone/AetherChat/Models.swift.
 * Converts LaTeX math markup into readable plain text (e.g. \frac{a}{b} becomes
 * (a) / (b), \sqrt becomes √) while leaving content inside code fences untouched.
 */
object ResponseNormalizer {

    fun displayText(response: String): String {
        val normalized = response
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        val lines = normalized.split("\n")
        var inCodeFence = false
        val output = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                output.add(line)
            } else if (inCodeFence) {
                output.add(line)
            } else {
                output.add(normalizeMath(line))
            }
        }

        return output.joinToString("\n")
    }

    private fun normalizeMath(source: String): String {
        var text = source
            .replace("$$", "")
            .replace("\\[", "")
            .replace("\\]", "")
            .replace("\\(", "")
            .replace("\\)", "")

        text = replaceTwoArgumentCommand(text, "frac") { numerator, denominator ->
            "(${normalizeMath(numerator)}) / (${normalizeMath(denominator)})"
        }
        text = replaceOneArgumentCommand(text, "sqrt") { argument ->
            "√(${normalizeMath(argument)})"
        }
        text = replaceOneArgumentCommand(text, "text") { argument ->
            normalizeMath(argument)
        }
        return normalizeFormula(text)
    }

    private fun normalizeFormula(source: String): String =
        source
            .replace("\\pm", "±")
            .replace("\\mp", "∓")
            .replace("\\times", "×")
            .replace("\\cdot", "·")
            .replace("\\leq", "≤")
            .replace("\\geq", "≥")
            .replace("\\neq", "≠")
            .replace("\\left", "")
            .replace("\\right", "")
            .replace("\\,", " ")
            .replace("\\!", "")
            .replace("\\%", "%")
            .replace("{", "(")
            .replace("}", ")")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun replaceOneArgumentCommand(
        source: String,
        command: String,
        transform: (String) -> String
    ): String {
        val marker = "\\$command{"
        val result = StringBuilder()
        var cursor = 0

        while (true) {
            val markerStart = source.indexOf(marker, cursor)
            if (markerStart < 0) break
            val openingBrace = markerStart + marker.length - 1
            val closingBrace = matchingBrace(source, openingBrace) ?: break
            result.append(source, cursor, markerStart)
            result.append(transform(source.substring(openingBrace + 1, closingBrace)))
            cursor = closingBrace + 1
        }

        result.append(source, cursor, source.length)
        return result.toString()
    }

    private fun replaceTwoArgumentCommand(
        source: String,
        command: String,
        transform: (String, String) -> String
    ): String {
        val marker = "\\$command"
        val result = StringBuilder()
        var cursor = 0

        while (true) {
            val markerStart = source.indexOf(marker, cursor)
            if (markerStart < 0) break
            val afterMarker = markerStart + marker.length
            val firstOpening = nextOpeningBrace(source, afterMarker) ?: break
            val firstClosing = matchingBrace(source, firstOpening) ?: break
            val secondOpening = nextOpeningBrace(source, firstClosing + 1) ?: break
            val secondClosing = matchingBrace(source, secondOpening) ?: break
            result.append(source, cursor, markerStart)
            val numerator = source.substring(firstOpening + 1, firstClosing)
            val denominator = source.substring(secondOpening + 1, secondClosing)
            result.append(transform(numerator, denominator))
            cursor = secondClosing + 1
        }

        result.append(source, cursor, source.length)
        return result.toString()
    }

    private fun nextOpeningBrace(source: String, after: Int): Int? {
        var cursor = after
        while (cursor < source.length) {
            if (source[cursor] == '{') return cursor
            if (!source[cursor].isWhitespace()) return null
            cursor += 1
        }
        return null
    }

    private fun matchingBrace(source: String, openingAt: Int): Int? {
        if (openingAt >= source.length || source[openingAt] != '{') return null
        var depth = 0
        var cursor = openingAt
        while (cursor < source.length) {
            if (source[cursor] == '{') {
                depth += 1
            } else if (source[cursor] == '}') {
                depth -= 1
                if (depth == 0) return cursor
            }
            cursor += 1
        }
        return null
    }
}
