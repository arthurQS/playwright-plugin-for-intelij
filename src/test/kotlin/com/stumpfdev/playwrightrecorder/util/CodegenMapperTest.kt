package com.stumpfdev.playwrightrecorder.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CodegenMapperTest {
    @Test
    fun extractStepsJavaScript() {
        val raw = """
            import { test } from '@playwright/test';
            test('x', async ({ page }) => {
              await page.goto('https://example.com');
              await page.getByRole('button', { name: 'Save' }).click();
            });
        """.trimIndent()

        val steps = CodegenMapper.extractSteps(TargetLanguage.JAVASCRIPT, raw)
        assertEquals(2, steps.size)
        assertEquals("await page.goto('https://example.com');", steps[0].text.trim())
    }

    @Test
    fun extractStepsPython() {
        val raw = """
            from playwright.sync_api import expect
            def test_x(page):
                page.goto('https://example.com')
                page.get_by_text('Save').click()
        """.trimIndent()

        val steps = CodegenMapper.extractSteps(TargetLanguage.PYTHON, raw)
        assertEquals(2, steps.size)
        assertEquals("page.goto('https://example.com')", steps[0].text.trim())
    }
}
