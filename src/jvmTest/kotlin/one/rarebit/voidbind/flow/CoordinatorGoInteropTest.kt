package one.rarebit.voidbind.flow

import java.io.File
import java.net.ServerSocket
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.Ed25519Engine
import one.rarebit.voidbind.Enrolment
import one.rarebit.voidbind.KeyRef
import one.rarebit.voidbind.Pairing
import one.rarebit.voidbind.UserIdentity
import one.rarebit.voidbind.crypto.Ed25519Group
import one.rarebit.voidbind.net.JdkHttpTransport
import one.rarebit.voidbind.net.WebLoginClient

/**
 * CROSS-LANGUAGE proof for the app-facing COORDINATORS (not just the raw clients):
 * the whole [UserIdentity] → [Enrolment] → [LoginApproval] / [DeviceAuthorization]
 * + [DevicePairing] stack driven against a LIVE voidbind-go. Skipped (not failed)
 * when `go`/the voidbind-go checkout is absent, so CI stays green.
 *
 *  - login: a coordinator-driven device approves a login on the real Go RP, which
 *    verifies the kmp-derived identity's self-enrolled cert + assertion.
 *  - pairing: the two coordinators authorise a new device THROUGH the real Go relay.
 */
class CoordinatorGoInteropTest {
    private val goDir = File(System.getProperty("user.home"), "Workspace/rarebit-one/voidbind-go")
    private fun goAvailable(): Boolean =
        goDir.isDirectory && System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .any { File(it, "go").canExecute() }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun buildCli(): File {
        val out = File.createTempFile("vb-coord-interop", "").apply { delete() }
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

    private fun softwareDevice(user: UserIdentity, seedByte: Int, now: Long): Pair<DeviceIdentity, String> {
        val seed = ByteArray(32) { (it + seedByte).toByte() }
        val enc = DeviceIdentity.generateEncryptionKey()
        val device = DeviceIdentity(
            signPublicKey = Ed25519Group.publicKeyFromSeed(seed),
            encPublicKey = enc.publicKey,
            encPrivateKey = enc.privateKey,
            signFn = { msg -> Ed25519Engine.sign(seed, msg) },
        )
        val cert = Enrolment.selfEnrol(user, device, issuedAt = now, lifetimeSeconds = 3600)
        return device to cert
    }

    @Test
    fun loginApprovalApprovesOnLiveGoRpForARecoveryDerivedIdentity() {
        assumeTrue(goAvailable(), "voidbind-go / go not available — skipping cross-language test")
        val cli = buildCli()
        val port = freePort()
        val base = "http://127.0.0.1:$port"

        // A kmp recovery-derived identity the Go RP pins.
        val user = UserIdentity.create()
        val now = System.currentTimeMillis() / 1000
        val (device, cert) = softwareDevice(user, seedByte = 13, now = now)

        val proc = ProcessBuilder(cli.absolutePath, "login-serve", "--addr", "127.0.0.1:$port", "--pin", user.userId.render())
            .redirectErrorStream(true).start()
        try {
            val http = JdkHttpTransport()
            waitReady { http.post("$base/login").status == 200 }

            // Browser side opens a login → a real voidbind:login QR.
            val created = WebLoginClient(http, base).createLogin()

            // The device approves it through the coordinator.
            val flow = LoginApproval(http, device, cert)
            val request = flow.begin(created.qr)
            flow.approve(request)

            val poll = WebLoginClient(http, base).poll(created.id)
            assertEquals("approved", poll.status, "the Go RP must approve the coordinator's assertion")
            assertTrue(!poll.token.isNullOrEmpty(), "the Go RP must mint a token")
            assertEquals(user.userId.render(), poll.user, "principal must be the recovery-derived kmp identity")
        } finally {
            proc.destroyForcibly()
        }
    }

    @Test
    fun coordinatorsPairANewDeviceThroughTheLiveGoRelay() {
        assumeTrue(goAvailable(), "voidbind-go / go not available — skipping cross-language test")
        val cli = buildCli()
        val port = freePort()
        val base = "http://127.0.0.1:$port"

        val proc = ProcessBuilder(cli.absolutePath, "relay", "--addr", "127.0.0.1:$port")
            .redirectErrorStream(true).start()
        try {
            val http = JdkHttpTransport()
            waitReady { http.post("$base/v1/sessions").status == 200 }

            val user = UserIdentity.create()
            val now = System.currentTimeMillis() / 1000
            val (newDevice, _) = softwareDevice(user, seedByte = 17, now = now)

            val auth = DeviceAuthorization(http, user, clock = { now }, pollIntervalMillis = 20)
            val pairing = DevicePairing(http, newDevice, pollIntervalMillis = 20)

            val invitation = auth.invite(base)
            var sasInitiator = ""
            var handshake: DevicePairing.Handshake? = null
            val tA = Thread { sasInitiator = auth.handshake(invitation) }
            val tB = Thread { handshake = pairing.begin(invitation.inviteQr) }
            tA.start(); tB.start(); tA.join(15_000); tB.join(15_000)

            val hs = handshake!!
            assertEquals(sasInitiator, hs.sas, "SAS must match through the live Go relay")
            assertEquals(Pairing.DIGITS, hs.sas.length)

            auth.authorise(invitation)
            val certToken = pairing.confirm(hs)

            val parsed = Cert.parse(certToken)
            assertTrue(parsed.verify(Ed25519Engine.verifier()))
            assertEquals(user.userId, parsed.cert.user)
            assertEquals(KeyRef.ed25519(newDevice.signPublicKey), parsed.cert.device)
        } finally {
            proc.destroyForcibly()
        }
    }
}
