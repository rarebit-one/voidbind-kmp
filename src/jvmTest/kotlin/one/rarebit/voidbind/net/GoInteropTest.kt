package one.rarebit.voidbind.net

import java.io.File
import java.net.ServerSocket
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.Ed25519Engine
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.Labels
import one.rarebit.voidbind.Pairing
import one.rarebit.voidbind.WebLogin

/**
 * The CROSS-LANGUAGE proof: drive a LIVE voidbind-go over HTTP from the Kotlin
 * clients. Skipped (not failed) when `go` or the voidbind-go checkout is absent —
 * so CI, which has neither, stays green — and RUN locally where both exist.
 *
 * Builds the `voidbind` CLI once, then:
 *  - relay: two Kotlin pairflow sides pair through the REAL Go relay → SAS match.
 *  - weblogin: a Kotlin device approves a login on the REAL Go RP, which verifies
 *    the Kotlin-signed cert + assertion and mints a token → proves Go accepts
 *    Kotlin's cert (v2) and assertion byte-for-byte.
 */
class GoInteropTest {
    private val goDir = File(System.getProperty("user.home"), "Workspace/rarebit-one/voidbind-go")
    private fun goAvailable(): Boolean =
        goDir.isDirectory && System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { File(it, "go").canExecute() }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun buildCli(): File {
        val out = File.createTempFile("vb-interop", "").apply { delete() }
        val p = ProcessBuilder("go", "build", "-o", out.absolutePath, "./cmd/voidbind")
            .directory(goDir).redirectErrorStream(true).start()
        val log = p.inputStream.readBytes().decodeToString()
        assertEquals(0, p.waitFor(), "go build failed: $log")
        return out
    }

    private fun waitReady(within: Long = 20_000, check: () -> Boolean) {
        val http = JdkHttpTransport()
        val deadline = System.currentTimeMillis() + within
        while (System.currentTimeMillis() < deadline) {
            try { if (check()) return } catch (_: Exception) {}
            http.sleep(100)
        }
        throw IllegalStateException("server not ready within ${within}ms")
    }

    @Test
    fun pairflowPairsThroughLiveGoRelay() {
        assumeTrue(goAvailable(), "voidbind-go / go not available — skipping cross-language test")
        val cli = buildCli()
        val port = freePort()
        val base = "http://127.0.0.1:$port"
        val proc = ProcessBuilder(cli.absolutePath, "relay", "--addr", "127.0.0.1:$port")
            .redirectErrorStream(true).start()
        try {
            val http = JdkHttpTransport()
            waitReady { http.post("$base/v1/sessions").status == 200 }

            val session = RelayClient.createSession(http, base)
            val user = Ed25519Engine.generate()
            val dev = Ed25519Engine.generate()
            val devEnc = ByteArray(32) { (it + 5).toByte() }
            val salt = ByteArray(32) { (it * 3 + 2).toByte() }
            val init = PairflowInitiator(
                RelayClient(http, base, session, RelayClient.ROLE_INITIATOR, pollIntervalMillis = 20),
                user.privateSeed, user.publicKey, salt, 1_724_700_000L, 7_776_000L,
            )
            val resp = PairflowResponder(
                RelayClient(http, base, session, RelayClient.ROLE_RESPONDER, pollIntervalMillis = 20),
                dev.publicKey, devEnc, salt,
            )
            var a = ""; var b = ""
            val ti = Thread { a = init.handshake() }
            val tr = Thread { b = resp.handshake() }
            ti.start(); tr.start(); ti.join(15_000); tr.join(15_000)
            assertEquals(a, b, "SAS must match through the live Go relay")
            assertEquals(Pairing.DIGITS, a.length)
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun deviceApprovesLoginOnLiveGoRp() {
        assumeTrue(goAvailable(), "voidbind-go / go not available — skipping cross-language test")
        val cli = buildCli()
        val port = freePort()
        val base = "http://127.0.0.1:$port"

        // A Kotlin-minted user identity the Go RP will pin.
        val user = Ed25519Engine.generate()
        val userId = KeyRef.ed25519(user.publicKey).render()

        val proc = ProcessBuilder(cli.absolutePath, "login-serve", "--addr", "127.0.0.1:$port", "--pin", userId)
            .redirectErrorStream(true).start()
        try {
            val http = JdkHttpTransport()
            waitReady { http.post("$base/login").status == 200 }

            val rp = WebLoginClient(http, base)
            val created = rp.createLogin()

            // The Kotlin device: a fresh signing key + a cert the user signs (v2).
            val dev = Ed25519Engine.generate()
            val now = System.currentTimeMillis() / 1000
            val certToken = Cert(
                version = Labels.CERT_VERSION, // 2 — required by Go's CertUser
                user = KeyRef.ed25519(user.publicKey),
                device = KeyRef.ed25519(dev.publicKey),
                deviceEnc = KeyRef.x25519(ByteArray(32) { (it + 9).toByte() }),
                issuedAt = now,
                expiresAt = now + 3600,
            ).encode { msg -> Ed25519Engine.sign(user.privateSeed, msg) }

            val chal = rp.fetchChallenge(created.id)
            val assertion = WebLogin.signAssertion(chal, certToken) { Ed25519Engine.sign(dev.privateSeed, it) }
            rp.approve(created.id, assertion) // Go verifies cert (rp) + assertion sig; 204 or throws

            val poll = rp.poll(created.id)
            assertEquals("approved", poll.status, "the Go RP must approve a valid Kotlin assertion")
            assertTrue(!poll.token.isNullOrEmpty(), "the Go RP must mint a session token")
            assertEquals(userId, poll.user, "the authenticated principal is the pinned Kotlin user")
        } finally {
            proc.destroyForcibly()
        }
    }
}
