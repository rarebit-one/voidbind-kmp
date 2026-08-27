package one.rarebit.voidbind.net

import one.rarebit.voidbind.crypto.MiniJson

/**
 * Client for the Voidbind dumb relay (voidbind-go `relay`): an opaque,
 * write-once, blob store addressed by (session, role, message-type) over HTTP.
 *
 * Wire (byte-identical to voidbind-go/relay/client.go):
 * ```
 * POST {base}/v1/sessions                       -> { "session_id": "..." }
 * PUT  {base}/v1/sessions/{id}/{role}/{type}      store opaque bytes (204)
 * GET  {base}/v1/sessions/{id}/{role}/{type}      read (200 bytes | 404 not yet)
 * ```
 * A client is bound to one session and its own [role]; [post] writes this role's
 * slot, [fetch] polls the PEER role's slot until it appears. The relay learns
 * nothing: every payload is a public value or ciphertext it cannot open.
 */
class RelayClient(
    private val http: HttpTransport,
    private val base: String,
    val session: String,
    val role: String,
    private val pollIntervalMillis: Long = 150,
    private val maxWaitMillis: Long = 60_000,
) {
    companion object {
        const val ROLE_INITIATOR = "initiator"
        const val ROLE_RESPONDER = "responder"

        /** Ask the relay to open a session and return its id. */
        fun createSession(http: HttpTransport, base: String): String {
            val resp = http.post(trimr(base) + "/v1/sessions")
            require(resp.status == 200) { "relay: create session: HTTP ${resp.status}" }
            val id = MiniJson.parseObject(resp.body.decodeToString())["session_id"] as? String
            require(!id.isNullOrEmpty()) { "relay: empty session id" }
            return id
        }

        private fun trimr(s: String) = s.trimEnd('/')
    }

    private fun peer(): String = if (role == ROLE_INITIATOR) ROLE_RESPONDER else ROLE_INITIATOR

    private fun slotUrl(role: String, type: String) =
        "${trimr(base)}/v1/sessions/$session/$role/$type"

    /** Write this role's [type] slot (write-once at the relay). */
    fun post(type: String, payload: ByteArray) {
        val resp = http.put(slotUrl(role, type), payload, "application/octet-stream")
        require(resp.status == 204) { "relay: post $role/$type: HTTP ${resp.status}" }
    }

    /** Poll the PEER role's [type] slot until present, returning its bytes. */
    fun fetch(type: String): ByteArray {
        val p = peer()
        var waited = 0L
        while (true) {
            val resp = http.get(slotUrl(p, type))
            when (resp.status) {
                200 -> return resp.body
                404 -> {
                    if (waited >= maxWaitMillis) throw RelayTimeout("relay: timed out waiting for $p/$type")
                    http.sleep(pollIntervalMillis)
                    waited += pollIntervalMillis
                }
                else -> throw IllegalStateException("relay: fetch $p/$type: HTTP ${resp.status}")
            }
        }
    }
}

/** Thrown when [RelayClient.fetch] gives up waiting for a peer slot. */
class RelayTimeout(message: String) : RuntimeException(message)
