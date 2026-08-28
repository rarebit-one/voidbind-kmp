package one.rarebit.voidbind.app.platform

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Presents a user-presence check. The device signing key's hardware wrapping key is
 * bound to a short post-authentication window ([one.rarebit.voidbind.DeviceKeyStore]);
 * when the engine hits [one.rarebit.voidbind.AuthenticationRequiredException], it
 * calls [authenticate], and on success retries the signature within the window.
 */
interface BiometricAuthenticator {
    /** Show the prompt and suspend until the user resolves it. True on success. */
    suspend fun authenticate(title: String, subtitle: String): Boolean
}

/** Backed by androidx `BiometricPrompt`; must be constructed with a [FragmentActivity]. */
class AndroidBiometricAuthenticator(private val activity: FragmentActivity) : BiometricAuthenticator {

    override suspend fun authenticate(title: String, subtitle: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    activity,
                    ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onAuthenticationFailed() {
                            // A single non-match — keep the prompt open for a retry.
                        }
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                    .build()
                prompt.authenticate(info)
            }
        }
}
