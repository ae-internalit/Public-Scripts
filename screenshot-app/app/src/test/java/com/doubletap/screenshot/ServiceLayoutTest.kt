package com.doubletap.screenshot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Layouts inflated by the accessibility service, rather than by an Activity,
 * must not reference theme attributes.
 *
 * A Service does not inherit the application's android:theme; it resolves
 * against the bare platform default, where AppCompat attributes do not exist.
 * Referencing one throws InflateException from the view constructor at
 * runtime, which no compiler or build step catches. That shipped three times
 * before it was traced, so it is asserted here instead.
 */
class ServiceLayoutTest {

    private val serviceInflatedLayouts = listOf("popup_quick_share.xml")

    private fun layoutDir(): File {
        // Unit tests run with the module directory as the working directory,
        // but tolerate being run from the repository root too.
        val candidates = listOf(
            File("src/main/res/layout"),
            File("app/src/main/res/layout"),
            File("screenshot-app/app/src/main/res/layout")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("layout directory not found from ${File(".").absolutePath}")
    }

    @Test
    fun `service inflated layouts avoid theme attributes`() {
        val dir = layoutDir()

        for (name in serviceInflatedLayouts) {
            val file = File(dir, name)
            assertTrue("missing layout: ${file.path}", file.isFile)

            val offending = file.readLines()
                .withIndex()
                .filter { (_, line) -> line.contains("?attr/") || line.contains("?android:attr") }
                .map { (i, line) -> "  line ${i + 1}: ${line.trim()}" }

            assertTrue(
                "$name is inflated by the service and must not use theme " +
                    "attributes, which the platform default theme cannot " +
                    "resolve:\n" + offending.joinToString("\n"),
                offending.isEmpty()
            )
        }
    }

    @Test
    fun `the popup layout still exists and has its buttons`() {
        val text = File(layoutDir(), "popup_quick_share.xml").readText()

        for (id in listOf("btnShare", "btnBlur", "btnOcr", "btnDelete", "btnDismiss")) {
            assertTrue("popup is missing $id", text.contains("@+id/$id"))
        }
        assertFalse("popup must not depend on an action bar theme", text.contains("?attr/"))
    }
}
