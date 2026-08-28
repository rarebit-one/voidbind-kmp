package one.rarebit.voidbind.flow

import one.rarebit.voidbind.DeviceIdentity
import one.rarebit.voidbind.LoginQr
import one.rarebit.voidbind.WebLogin
import one.rarebit.voidbind.net.HttpTransport
import one.rarebit.voidbind.net.WebLoginClient

/**
 * The device side of **web QR-login** as one app flow: the user scans a
 * `voidbind:login?rp=&id=` QR on a browser, the app shows WHAT they are signing
 * into, and — only after the human taps Approve — the device signs the RP's
 * challenge with its hardware key and submits the assertion. The RP then verifies
 * it offline against the pinned user key and mints a short-lived session token.
 *
 * Two steps so the biometric prompt lands at the human's decision, not at scan:
 *  1. [begin] decodes the QR and FETCHES the challenge from the RP (the RP is the
 *     source of truth for what is signed) → a [Request] the approval sheet shows.
 *  2. [approve] signs the exact fetched challenge and POSTs the assertion.
 *
 * Blocking (network); run it off the main thread. Wraps [WebLoginClient] +
 * [WebLogin.signAssertion] — every signed byte is byte-identical to voidbind-go.
 */
class LoginApproval(
    private val http: HttpTransport,
    private val device: DeviceIdentity,
    /** This device's enrolment cert token; the signing key MUST be the one it names. */
    private val enrolmentCert: String,
) {
    /**
     * What the approval sheet shows the human. [audience] is the RP origin the
     * challenge binds; [expiresAt] is unix seconds (render a live countdown).
     */
    class Request internal constructor(
        val rp: String,
        val loginId: String,
        val audience: String,
        val expiresAt: Long,
        internal val challenge: WebLogin.Challenge,
    )

    /** Decode a scanned login QR, fetch the challenge, and return what to show. */
    @Throws(Exception::class)
    fun begin(loginQr: String): Request = begin(LoginQr.decode(loginQr))

    /** Same, when the Scan screen already parsed the QR via [one.rarebit.voidbind.VoidbindQr]. */
    @Throws(Exception::class)
    fun begin(parsed: LoginQr.Parsed): Request {
        val client = WebLoginClient(http, parsed.rp)
        val challenge = client.fetchChallenge(parsed.id)
        return Request(parsed.rp, parsed.id, challenge.audience, challenge.expiresAt, challenge)
    }

    /**
     * After the human confirms: sign the fetched challenge with the device key
     * (biometric-gated inside [DeviceIdentity.sign]) and submit the assertion.
     * Throws if the RP refuses it (expired, unpinned device, replay). `@Throws` so
     * the refusal is catchable in Swift (a retry prompt), not a crash.
     */
    @Throws(Exception::class)
    fun approve(request: Request) {
        val assertion = WebLogin.signAssertion(request.challenge, enrolmentCert) { message ->
            device.sign(message)
        }
        WebLoginClient(http, request.rp).approve(request.loginId, assertion)
    }
}
