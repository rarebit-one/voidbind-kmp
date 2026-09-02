package one.rarebit.cruciform.handoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Regression for the 0.5.1/0.5.2 bug where "Send to heyarr on this phone" never
 * appeared: the manifest `<queries>` declared only `android:scheme`, but the RPs
 * register `scheme://pair` (scheme + host), and Android 11+ package visibility only
 * grants a query whose data matches the target filter — so `resolveActivity` returned
 * null for an installed app. This reads the real manifest and asserts each KNOWN
 * target has a `<queries><intent>` with VIEW + the same scheme AND host as the URI
 * `RpPairHandoff` / `RpPairLauncher` actually fire. Pure JVM, no Android runtime.
 */
class RpPairManifestQueriesTest {

    private val android = "http://schemas.android.com/apk/res/android"

    private data class QueryData(val scheme: String?, val host: String?)

    private fun manifestQueries(): List<QueryData> {
        // Gradle runs unit tests with the module dir as the working directory.
        val manifest = listOf("src/main/AndroidManifest.xml", "androidApp/src/main/AndroidManifest.xml")
            .map(::File).firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val out = ArrayList<QueryData>()
        val queries = doc.getElementsByTagName("queries")
        for (q in 0 until queries.length) {
            val intents = (queries.item(q) as org.w3c.dom.Element).getElementsByTagName("intent")
            for (i in 0 until intents.length) {
                val intent = intents.item(i) as org.w3c.dom.Element
                val actions = intent.getElementsByTagName("action")
                val isView = (0 until actions.length).any {
                    (actions.item(it) as org.w3c.dom.Element).getAttributeNS(android, "name") == "android.intent.action.VIEW"
                }
                if (!isView) continue
                val datas = intent.getElementsByTagName("data")
                for (d in 0 until datas.length) {
                    val data = datas.item(d) as org.w3c.dom.Element
                    out += QueryData(
                        scheme = data.getAttributeNS(android, "scheme").ifEmpty { null },
                        host = data.getAttributeNS(android, "host").ifEmpty { null },
                    )
                }
            }
        }
        return out
    }

    @Test
    fun everyKnownTargetHasAViewQueryMatchingSchemeAndHost() {
        val queries = manifestQueries()
        assertTrue("no <queries><intent> VIEW data in manifest", queries.isNotEmpty())
        RpPairHandoff.KNOWN.forEach { t ->
            val (scheme, host) = schemeAndHost(t.callbackBase)
            assertEquals(t.callbackBase, "pair", host)
            val match = queries.firstOrNull { it.scheme == scheme && it.host == host }
            assertTrue(
                "manifest <queries> has no VIEW intent with scheme=$scheme host=$host for ${t.appName}; " +
                    "a scheme-only query does not grant visibility to the RP's scheme://pair filter. Found: $queries",
                match != null,
            )
        }
    }

    @Test
    fun theProbeUriCarriesTheSameSchemeAndHostAsTheManifestQuery() {
        val queries = manifestQueries()
        RpPairHandoff.KNOWN.forEach { t ->
            val probe = RpPairLauncher.probeUri(t)
            val (scheme, host) = schemeAndHost(probe)
            assertTrue(probe, probe.startsWith("${t.callbackBase}?${RpPairHandoff.INVITE}="))
            assertTrue("probe $probe has no matching manifest query", queries.any { it.scheme == scheme && it.host == host })
            // And the real handoff URI resolves the same way as the probe.
            val real = RpPairHandoff.uriFor(t, "voidbind:pair?relay=x&session=s&usr=ed25519%3A00&v=3")
            assertEquals(schemeAndHost(probe), schemeAndHost(real))
        }
    }

    /** `scheme://host[?...]` → (scheme, host), no android.net.Uri on the JVM. */
    private fun schemeAndHost(uri: String): Pair<String, String> {
        val scheme = uri.substringBefore("://")
        val host = uri.substringAfter("://").takeWhile { it != '?' && it != '/' && it != '#' }
        return scheme to host
    }
}
