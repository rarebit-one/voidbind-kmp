package one.rarebit.cruciform.handoff

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Discovery visibility (ADR-0009 / #39): Cruciform finds RP handoff targets by querying
 * `PackageManager` for a VIEW intent carrying [RpPairHandoff.CATEGORY_RP_HANDOFF], and
 * Android 11+ package visibility only grants that query if the manifest declares a
 * matching `<queries>` entry. This reads the real manifest and asserts ONE generic
 * `<queries><intent>` with VIEW + the shared category exists — replacing the former
 * per-scheme `<data>` entries that each new RP used to need. Pure JVM, no Android runtime.
 */
class RpPairManifestQueriesTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private data class QueryIntent(val actions: List<String>, val categories: List<String>, val schemes: List<String>)

    private fun manifestQueryIntents(): List<QueryIntent> {
        // Gradle runs unit tests with the module dir as the working directory.
        val manifest = listOf("src/main/AndroidManifest.xml", "androidApp/src/main/AndroidManifest.xml")
            .map(::File).firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val out = ArrayList<QueryIntent>()
        val queries = doc.getElementsByTagName("queries")
        for (q in 0 until queries.length) {
            val intents = (queries.item(q) as org.w3c.dom.Element).getElementsByTagName("intent")
            for (i in 0 until intents.length) {
                val intent = intents.item(i) as org.w3c.dom.Element
                out += QueryIntent(
                    actions = namesOf(intent, "action"),
                    categories = namesOf(intent, "category"),
                    schemes = attrsOf(intent, "data", "scheme"),
                )
            }
        }
        return out
    }

    private fun namesOf(intent: org.w3c.dom.Element, tag: String): List<String> {
        val els = intent.getElementsByTagName(tag)
        return (0 until els.length).map { (els.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") }
    }

    private fun attrsOf(intent: org.w3c.dom.Element, tag: String, attr: String): List<String> {
        val els = intent.getElementsByTagName(tag)
        return (0 until els.length).mapNotNull { (els.item(it) as org.w3c.dom.Element).getAttributeNS(android, attr).ifEmpty { null } }
    }

    @Test
    fun theSharedRpHandoffCategoryQueryIsDeclared() {
        val intents = manifestQueryIntents()
        assertTrue("no <queries><intent> in manifest", intents.isNotEmpty())
        val discovery = intents.firstOrNull {
            "android.intent.action.VIEW" in it.actions && RpPairHandoff.CATEGORY_RP_HANDOFF in it.categories
        }
        assertTrue(
            "manifest <queries> has no VIEW intent carrying the ${RpPairHandoff.CATEGORY_RP_HANDOFF} category; " +
                "without it, Android 11+ package visibility hides every RP handoff advert. Found: $intents",
            discovery != null,
        )
        // The discovery query is data-less by design: a data-bearing probe cannot match
        // an RP's data-less advert filter, and vice versa (see RpPairHandoff.CATEGORY_RP_HANDOFF).
        assertTrue("the RP_HANDOFF discovery query must not carry <data>", discovery!!.schemes.isEmpty())
    }

    @Test
    fun theProbeIntentUsesTheSameCategoryAsTheManifestQuery() {
        // The launcher's probe (RpPairLauncher.adverts) and the manifest <queries> entry
        // must name the identical category, or the query is granted visibility to nothing.
        val intents = manifestQueryIntents()
        assertTrue(
            "no manifest query names the launcher's probe category ${RpPairHandoff.CATEGORY_RP_HANDOFF}",
            intents.any { RpPairHandoff.CATEGORY_RP_HANDOFF in it.categories },
        )
    }
}
