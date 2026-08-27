package one.rarebit.voidbind.net

import one.rarebit.voidbind.WebLogin
import one.rarebit.voidbind.crypto.Base64Url
import one.rarebit.voidbind.crypto.MiniJson

/**
 * Client for the Voidbind web QR-login (voidbind-go `weblogin`): the DEVICE side
 * (approve a login) and, for a browser stand-in, the create/poll side.
 *
 * Wire (byte-identical to voidbind-go/weblogin/handler.go):
 * ```
 * POST {rp}/login                    -> { "id":..., "qr":... }       (browser)
 * GET  {rp}/login/{id}               -> { "status", "token"?, "user"? } (browser polls)
 * GET  {rp}/login/{id}/challenge     -> { id, nonce(b64url), audience, expires_at } (device)
 * POST {rp}/login/{id}/approve  {cert,sig}  -> 204                    (device)
 * ```
 * The device path is the app's job: [fetchChallenge] then [approve] with an
 * assertion signed via [WebLogin.signAssertion]. The browser path
 * ([createLogin] / [poll]) is here so a test — or an in-app WebView shim — can
 * drive the whole flow.
 */
class WebLoginClient(
    private val http: HttpTransport,
    private val rpBase: String,
) {
    private fun trimr(s: String) = s.trimEnd('/')

    class Created(val id: String, val qr: String)
    class Poll(val status: String, val token: String?, val user: String?)

    /** Browser: start a login. */
    fun createLogin(): Created {
        val resp = http.post(trimr(rpBase) + "/login")
        require(resp.status == 200) { "weblogin: create: HTTP ${resp.status}" }
        val o = MiniJson.parseObject(resp.body.decodeToString())
        return Created(o["id"] as String, o["qr"] as String)
    }

    /** Browser: poll a login's status (and token once approved). */
    fun poll(id: String): Poll {
        val resp = http.get(trimr(rpBase) + "/login/" + id)
        require(resp.status == 200) { "weblogin: poll: HTTP ${resp.status}" }
        val o = MiniJson.parseObject(resp.body.decodeToString())
        return Poll(o["status"] as String, o["token"] as? String, o["user"] as? String)
    }

    /** Device: fetch the exact challenge to sign (the RP is the source of truth). */
    fun fetchChallenge(id: String): WebLogin.Challenge {
        val resp = http.get(trimr(rpBase) + "/login/" + id + "/challenge")
        require(resp.status == 200) { "weblogin: fetch challenge: HTTP ${resp.status}" }
        val o = MiniJson.parseObject(resp.body.decodeToString())
        return WebLogin.Challenge(
            id = o["id"] as String,
            nonce = Base64Url.decode(o["nonce"] as String),
            audience = o["audience"] as String,
            expiresAt = o["expires_at"] as Long,
        )
    }

    /** Device: submit a signed assertion to approve the login. */
    fun approve(id: String, assertion: WebLogin.Assertion) {
        val body = MiniJson.encodeObject(
            listOf("cert" to assertion.cert, "sig" to assertion.sig),
        ).encodeToByteArray()
        val resp = http.post(trimr(rpBase) + "/login/" + id + "/approve", body, "application/json")
        require(resp.status == 204) { "weblogin: approve refused: HTTP ${resp.status}" }
    }
}
