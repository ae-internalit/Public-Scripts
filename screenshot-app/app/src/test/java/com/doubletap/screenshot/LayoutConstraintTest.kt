package com.doubletap.screenshot

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every direct child of a ConstraintLayout needs at least one constraint.
 *
 * A child with none is not a build error and draws no warning at runtime: the
 * layout simply parks it at (0,0), behind whatever is declared after it. That
 * is what happened to the gallery's Export PDF button, which rendered under the
 * title bar and behind the screenshot grid for several releases while looking,
 * from the outside, like a feature that was never built.
 */
class LayoutConstraintTest {

    private val constraintPrefix = "layout_constraint"
    private val resAuto = "http://schemas.android.com/apk/res-auto"
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun layoutDir(): File {
        val candidates = listOf(
            File("src/main/res/layout"),
            File("app/src/main/res/layout"),
            File("screenshot-app/app/src/main/res/layout")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("layout directory not found from ${File(".").absolutePath}")
    }

    private fun parse(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun constraintCount(element: Element): Int {
        var found = 0
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val node = attributes.item(i)
            if (node.namespaceURI == resAuto && node.localName.startsWith(constraintPrefix)) {
                found++
            }
        }
        return found
    }

    @Test
    fun `no child of a ConstraintLayout is left unconstrained`() {
        val offenders = mutableListOf<String>()

        for (file in layoutDir().listFiles { f -> f.extension == "xml" }.orEmpty().sortedBy { it.name }) {
            val root = parse(file)
            if (!root.tagName.endsWith("ConstraintLayout")) continue

            val children = root.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                if (constraintCount(child) > 0) continue

                val id = child.getAttributeNS(androidNs, "id").ifEmpty { "(no id)" }
                offenders += "  ${file.name}: <${child.tagName}> $id"
            }
        }

        assertTrue(
            "these views sit inside a ConstraintLayout with no constraints, so " +
                "the layout pins them to the top-left corner where later " +
                "siblings cover them:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `the gallery grid stops above the export button`() {
        val root = parse(File(layoutDir(), "activity_history.xml"))

        var recyclerBottom: String? = null
        var exportBottom: String? = null

        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            when (child.getAttributeNS(androidNs, "id")) {
                "@+id/historyRecycler" ->
                    recyclerBottom = child.getAttributeNS(resAuto, "layout_constraintBottom_toTopOf")
                "@+id/btnExportPdf" ->
                    exportBottom = child.getAttributeNS(resAuto, "layout_constraintBottom_toBottomOf")
            }
        }

        assertTrue(
            "the export button must be pinned to the bottom of the screen, " +
                "not left to fall behind the grid (found: '$exportBottom')",
            exportBottom == "parent"
        )
        assertTrue(
            "the grid must end above the export button so the button is " +
                "reachable (found: '$recyclerBottom')",
            recyclerBottom == "@id/btnExportPdf"
        )
    }
}
