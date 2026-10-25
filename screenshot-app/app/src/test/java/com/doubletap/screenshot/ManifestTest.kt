package com.doubletap.screenshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Declarations that only fail at runtime.
 *
 * An Activity missing from the manifest throws ActivityNotFoundException the
 * first time something tries to open it, and an editor that loses its
 * intent-filters simply stops appearing under Edit and Share in other apps,
 * with nothing anywhere reporting why.
 */
class ManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun manifest(): Element {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("screenshot-app/app/src/main/AndroidManifest.xml")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("manifest not found from ${File(".").absolutePath}")

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun activities(): Map<String, Element> {
        val nodes = manifest().getElementsByTagName("activity")
        val found = mutableMapOf<String, Element>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            found[element.getAttributeNS(androidNs, "name")] = element
        }
        return found
    }

    private fun actionsOf(activity: Element): List<String> {
        val actions = mutableListOf<String>()
        val filters = activity.getElementsByTagName("intent-filter")
        for (i in 0 until filters.length) {
            val filter = filters.item(i) as Element
            val list = filter.getElementsByTagName("action")
            for (j in 0 until list.length) {
                actions += (list.item(j) as Element).getAttributeNS(androidNs, "name")
            }
        }
        return actions
    }

    @Test
    fun `every activity the app starts is declared`() {
        val declared = activities().keys
        val needed = listOf(
            ".MainActivity", ".HistoryActivity", ".ViewerActivity",
            ".BlurEditorActivity", ".OcrActivity", ".ExcludedAppsActivity",
            ".ScreenRecordActivity", ".DeleteActivity"
        )
        for (name in needed) {
            assertTrue("$name is missing from the manifest", name in declared)
        }
    }

    @Test
    fun `the editor is reachable from other apps`() {
        val editor = activities()[".BlurEditorActivity"]
            ?: error("BlurEditorActivity is missing from the manifest")

        assertEquals(
            "the editor must stay exported or it disappears from Edit and Share",
            "true",
            editor.getAttributeNS(androidNs, "exported")
        )

        val actions = actionsOf(editor)
        assertTrue(
            "the editor needs an ACTION_EDIT filter (found: $actions)",
            "android.intent.action.EDIT" in actions
        )
        assertTrue(
            "the editor needs an ACTION_SEND filter (found: $actions)",
            "android.intent.action.SEND" in actions
        )
    }
}
