package one.rarebit.cruciform.domain

/** A declined or dismissed prompt. */
internal val CANCELLED_FAILURE =
    EngineFailure("Authentication cancelled.", EngineFailure.Kind.CANCELLED, retryable = false)

internal fun <T> internalFailure(message: String): EngineResult<T> {
    val failure = EngineFailure(message, EngineFailure.Kind.INTERNAL, retryable = false)
    return EngineResult.Failed(failure)
}

internal fun <T> cancelledFailure(): EngineResult<T> = EngineResult.Failed(CANCELLED_FAILURE)

/**
 * A destructive/authority act asked for a strong biometric and this device has
 * none enrolled. We deliberately do NOT fall back to the screen-lock credential
 * (that is the exact hole strong-only closes), so the honest answer is to point
 * the user at recovery. Not retryable — retrying without enrolling a biometric
 * hits the same wall.
 */
internal fun <T> strongBiometricRequiredFailure(): EngineResult<T> = EngineResult.Failed(
    EngineFailure(
        "This needs a fingerprint or face unlock — your PIN can't authorise it. " +
            "Enrol a biometric on this device, or use another device or your recovery secret.",
        EngineFailure.Kind.INTERNAL,
        retryable = false,
    ),
)
