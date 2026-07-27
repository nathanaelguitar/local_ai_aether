package com.nathanaelguitar.canopychat

import com.nathanaelguitar.canopychat.core.ResponseNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseNormalizerTest {

    @Test
    fun fracBecomesDivision() {
        assertEquals(
            "The answer is (1) / (2).",
            ResponseNormalizer.displayText("The answer is \\frac{1}{2}.")
        )
    }

    @Test
    fun sqrtBecomesRadical() {
        assertEquals("√(16)", ResponseNormalizer.displayText("\\sqrt{16}"))
    }

    @Test
    fun nestedCommandsNormalizeInsideOut() {
        assertEquals(
            "(√(2)) / ((1) / (3))",
            ResponseNormalizer.displayText("\\frac{\\sqrt{2}}{\\frac{1}{3}}")
        )
    }

    @Test
    fun textCommandUnwraps() {
        assertEquals("where x is real", ResponseNormalizer.displayText("\\text{where } x \\text{ is real}"))
    }

    @Test
    fun displayMathDelimitersAreStripped() {
        assertEquals("x = 1", ResponseNormalizer.displayText("\$\$x = 1\$\$"))
        assertEquals("x = 1", ResponseNormalizer.displayText("\\[ x = 1 \\]"))
        assertEquals("x = 1", ResponseNormalizer.displayText("\\( x = 1 \\)"))
    }

    @Test
    fun operatorsAndRelationsAreReplaced() {
        assertEquals("a ± b", ResponseNormalizer.displayText("a \\pm b"))
        assertEquals("a ∓ b", ResponseNormalizer.displayText("a \\mp b"))
        assertEquals("a × b", ResponseNormalizer.displayText("a \\times b"))
        assertEquals("a · b", ResponseNormalizer.displayText("a \\cdot b"))
        assertEquals("a ≤ b", ResponseNormalizer.displayText("a \\leq b"))
        assertEquals("a ≥ b", ResponseNormalizer.displayText("a \\geq b"))
        assertEquals("a ≠ b", ResponseNormalizer.displayText("a \\neq b"))
    }

    @Test
    fun bracesBecomeParentheses() {
        assertEquals("x_(i)", ResponseNormalizer.displayText("x_{i}"))
    }

    @Test
    fun leftRightSpacingAndPercentAreNormalized() {
        assertEquals("( a ) %", ResponseNormalizer.displayText("\\left( a \\right) \\%"))
        assertEquals("a b", ResponseNormalizer.displayText("a\\,b"))
        assertEquals("ab", ResponseNormalizer.displayText("a\\!b"))
    }

    @Test
    fun whitespaceCollapsesAndLineIsTrimmed() {
        assertEquals("hello world", ResponseNormalizer.displayText("  hello    world  "))
    }

    @Test
    fun unbalancedFracIsLeftAloneAsideFromBraceConversion() {
        // With no closing brace the command cannot be transformed; the surviving
        // lone "{" still becomes "(" in the formula pass, matching iOS.
        assertEquals("\\frac(1", ResponseNormalizer.displayText("\\frac{1"))
    }

    @Test
    fun codeFencesAreNotAltered() {
        val source = "Before \\frac{1}{2}\n```\n\\frac{a}{b} stays $$ raw $$\n```\nAfter \\sqrt{4}"
        val expected = "Before (1) / (2)\n```\n\\frac{a}{b} stays $$ raw $$\n```\nAfter √(4)"
        assertEquals(expected, ResponseNormalizer.displayText(source))
    }

    @Test
    fun codeFenceWithLeadingWhitespaceStillToggles() {
        val source = "  ```kotlin\nval x = \"\\frac{1}{2}\"\n```"
        assertEquals(source, ResponseNormalizer.displayText(source))
    }

    @Test
    fun crlfLineEndingsAreNormalized() {
        assertEquals("a\nb", ResponseNormalizer.displayText("a\r\nb"))
        assertEquals("a\nb", ResponseNormalizer.displayText("a\rb"))
    }

    @Test
    fun emptyStringPassesThrough() {
        assertEquals("", ResponseNormalizer.displayText(""))
    }
}
