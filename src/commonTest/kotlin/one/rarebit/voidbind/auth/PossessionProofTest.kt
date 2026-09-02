package one.rarebit.voidbind.auth

import one.rarebit.voidbind.Cert
import one.rarebit.voidbind.Ed25519Engine
import one.rarebit.voidbind.Ed25519Signer
import one.rarebit.voidbind.crypto.Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-language golden vectors against the Go implementation relying parties run
 * (voidbind-go v0.5.0 `enrolment.SignPossession`). Both vectors were minted by the
 * REAL Go code with fixed seeds and clocks (Ed25519 is deterministic), so a
 * byte-for-byte match here proves a proof this library mints is what
 * `enrolment.VerifyPossession` / heyarr's `deviceauth.Verify` accepts.
 *
 * If a constant ever has to change to make this pass, the wire format broke — stop
 * and investigate. The same vectors, as JSON, live in
 * `src/jvmTest/resources/vectors/` (see `DeviceSchemeVectorTest`).
 */
class PossessionProofTest {

    // Vector A — heyarr-core's own crosscompat golden cert (user seed 0x01*32, device
    // seed 0x02*32, iat 1700000000) + a proof minted by Go `SignPossession(dev, cert,
    // 1700000000, 0)`. Carried over from heyarr-mobile's PossessionProofTest.
    private val certA = "eyJ2IjoyLCJ1c3IiOiJlZDI1NTE5OjhhODhlM2RkNzQwOWYxOTVmZDUyZGIyZDNjYmE1ZDcyY2E2NzA5YmYxZDk0MTIxYmYzNzQ4ODAxYjQwZjZmNWMiLCJkZXYiOiJlZDI1NTE5OjgxMzk3NzBlYTg3ZDE3NWY1NmEzNTQ2NmMzNGM3ZWNjY2I4ZDhhOTFiNGVlMzdhMjVkZjYwZjViOGZjOWIzOTQiLCJkZW5jIjoieDI1NTE5OjAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMwMzAzMDMiLCJpYXQiOjE3MDAwMDAwMDAsImV4cCI6MTcwNzc3NjAwMH0.yUnLKnvDZ8YtkgV9zf5eRrYHes5osqzzGlVHXcFSPiuIuVM0jmcdGH4qOQA-UCla_9qwSK7VPpXSfsTbSY_JBA"
    private val proofA = "eyJ2IjoyLCJjcnQiOiJsQ2ViWTBpTTM0SGM0Z3RGUERtZ1o2S0pMWWE0ejc0YklhU1hSWFV1d01ZIiwiaWF0IjoxNzAwMDAwMDAwLCJleHAiOjE3MDAwMDAxMjB9.2Ta_JOte2RDRHBFrNvOToo68--ea7TIfRjFPNGV-JtvQiXVb_hfE1gQCA9RwlbHyFJ9Mp0AZ5G_u19U1jB-FAg"
    private val deviceSeedA = ByteArray(32) { 0x02 }
    private val nowA = 1_700_000_000L

    // Vector B — the Device-scheme vector minted by a scratch Go test (device seed
    // 0x80..0x9f, cert iat 1788307200, possession now 1788350400); the JSON copy is
    // src/jvmTest/resources/vectors/device-scheme-vector.json.
    private val certB = "eyJ2IjoyLCJ1c3IiOiJlZDI1NTE5OjAzYTEwN2JmZjNjZTEwYmUxZDcwZGQxOGU3NGJjMDk5NjdlNGQ2MzA5YmE1MGQ1ZjFkZGM4NjY0MTI1NTMxYjgiLCJkZXYiOiJlZDI1NTE5OmNkMTRiMzdmOTU2ZTk1MzE5NGZmN2ZiNzNiM2Q4MWRjYzU2MWQ2MWE3NTM4MDk0YjdjM2UxYTY0M2VlNWYzYWEiLCJkZW5jIjoieDI1NTE5OjAwMTEyMjMzNDQ1NTY2Nzc4ODk5YWFiYmNjZGRlZWZmMDAxMTIyMzM0NDU1NjY3Nzg4OTlhYWJiY2NkZGVlZmYiLCJpYXQiOjE3ODgzMDcyMDAsImV4cCI6MTc5NjA4MzIwMH0.3VwIUF7Bg1fGQgZ8lwGUvzjpNf2FwoaP-oHcrtleTFaLMiGS2AuljHoBSpOivIl1cJ5ue61_Ci50xX7GPFWCCA"
    private val proofB = "eyJ2IjoyLCJjcnQiOiJJdG01WGo5Vmtta2NkQWNOV3JjNEM0QU1LeVppTmx2cFRZM2dfS2FtakxJIiwiaWF0IjoxNzg4MzUwNDAwLCJleHAiOjE3ODgzNTA1MjB9.Qrj11oz4bLp_Zy8xWcHzQkhvYsjcCdy69LGGRcoCABPnlz3WynYLQwVFuxoVlYkn024FaXIDhXGadMAfR5g5Bw"
    private val deviceSeedB = Hex.decode("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val nowB = 1_788_350_400L

    private val verifier = Ed25519Engine.verifier()
    private fun signer(seed: ByteArray) = Ed25519Signer { Ed25519Engine.sign(seed, it) }
    private fun devicePub(token: String) = Cert.parse(token).cert.device.bytes

    @Test
    fun signingBytesMatchGoJson() {
        assertEquals(
            """{"v":2,"crt":"Itm5Xj9VkmkcdAcNWrc4C4AMKyZiNlvpTY3g_KamjLI","iat":1788350400,"exp":1788350520}""",
            PossessionProof.signingBytes(certB, nowB, nowB + 120).decodeToString(),
        )
        assertEquals("lCebY0iM34Hc4gtFPDmgZ6KJLYa4z74bIaSXRXUuwMY", PossessionProof.certHash(certA))
    }

    @Test
    fun mintsTheExactGoProof_vectorA() {
        assertEquals(proofA, PossessionProof.mint(certA, signer(deviceSeedA), nowA))
    }

    @Test
    fun mintsTheExactGoProof_vectorB() {
        assertEquals(proofB, PossessionProof.mint(certB, signer(deviceSeedB), nowB))
        // A non-positive ttl means the Go default, as in SignPossession.
        assertEquals(proofB, PossessionProof.mint(certB, signer(deviceSeedB), nowB, ttlSeconds = 0))
    }

    @Test
    fun certVectorsVerifyAgainstTheirUserKeyAndNameOurDeviceKey() {
        for (token in listOf(certA, certB)) {
            val parsed = Cert.parse(token)
            assertEquals(2, parsed.cert.version)
            assertTrue(parsed.verify(verifier), "cert signature by usr")
        }
        // The device key the cert names is the one our seed derives — else a proof could never verify.
        assertEquals(
            "ed25519:cd14b37f956e953194ff7fb73b3d81dcc561d61a7538094b7c3e1a643ee5f3aa",
            Cert.parse(certB).cert.device.render(),
        )
    }

    @Test
    fun goProofsVerifyAndParse() {
        val p = PossessionProof.verify(proofB, devicePub(certB), certB, nowB + 1, verifier)
        assertEquals(PossessionProof.Payload(2, "Itm5Xj9VkmkcdAcNWrc4C4AMKyZiNlvpTY3g_KamjLI", nowB, nowB + 120), p)
        assertEquals(p, PossessionProof.parse(proofB))
    }

    @Test
    fun verifyMirrorsGoWindow() {
        val pub = devicePub(certA)
        // honoured 1s before expiry; expired AT ttl (strict); honoured half a skew early; refused 2 skews early
        PossessionProof.verify(proofA, pub, certA, nowA + 119, verifier)
        assertEquals(PossessionProof.Reason.EXPIRED, refused { PossessionProof.verify(proofA, pub, certA, nowA + 120, verifier) })
        PossessionProof.verify(proofA, pub, certA, nowA - 15, verifier)
        PossessionProof.verify(proofA, pub, certA, nowA - 30, verifier)
        assertEquals(PossessionProof.Reason.NOT_YET_VALID, refused { PossessionProof.verify(proofA, pub, certA, nowA - 31, verifier) })
        assertEquals(PossessionProof.Reason.NOT_YET_VALID, refused { PossessionProof.verify(proofA, pub, certA, nowA - 60, verifier) })
    }

    @Test
    fun verifyRefusesWrongCertTamperingAndGarbage() {
        val pub = devicePub(certA)
        assertEquals(PossessionProof.Reason.WRONG_CERT, refused { PossessionProof.verify(proofA, pub, certB, nowA + 1, verifier) })
        assertEquals(PossessionProof.Reason.BAD_SIGNATURE, refused { PossessionProof.verify(proofA, devicePub(certB), certA, nowA + 1, verifier) })
        val flipped = proofA.substring(0, 5) + (if (proofA[5] == 'A') 'B' else 'A') + proofA.substring(6)
        assertEquals(PossessionProof.Reason.BAD_SIGNATURE, refused { PossessionProof.verify(flipped, pub, certA, nowA + 1, verifier) })
        assertEquals(PossessionProof.Reason.MALFORMED, refused { PossessionProof.verify("no-dot", pub, certA, nowA + 1, verifier) })
        assertEquals(PossessionProof.Reason.MALFORMED, refused { PossessionProof.verify("!!.!!", pub, certA, nowA + 1, verifier) })
        assertEquals(PossessionProof.Reason.MALFORMED, refused { PossessionProof.parse("no-dot") })
    }

    @Test
    fun mintRefusesAnEmptyCertAndANonEd25519Signature() {
        assertFailsWith<IllegalArgumentException> { PossessionProof.mint("", signer(deviceSeedA), nowA) }
        assertFailsWith<IllegalArgumentException> { PossessionProof.mint(certA, Ed25519Signer { ByteArray(63) }, nowA) }
    }

    private fun refused(block: () -> Unit): PossessionProof.Reason =
        assertFailsWith<PossessionProof.Refused> { block() }.reason
}
