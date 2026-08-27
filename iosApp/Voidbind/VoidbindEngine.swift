import Foundation
import Security
import Voidbind

/// The iOS mirror of the Android `DeviceVoidbindEngine`: it ties the commonMain
/// brain to the platform — the Secure-Enclave-sealed device signing key
/// (`DeviceKeyStore`), a persisted X25519 encryption keypair, `UserIdentity`
/// create/restore, `Enrolment`, and the three flow coordinators over a
/// `URLSessionHttpTransport`. The SwiftUI ViewModels call this off the main thread.
///
/// Deliberately thin: all identity crypto + wire lives in the KMP library; this is
/// only provisioning + wiring, the same split the Android engine keeps.
///
/// > ⚠️ NOT COMPILED IN CI — reviewed scaffold; a device run (see
/// > docs/DEVICE-TESTING.md) is the acceptance. The single most likely first-build
/// > fix is the `signFn` closure's parameter nullability (Kotlin function-type
/// > params can bridge as optional in the generated header).
public final class VoidbindEngine {

    public static let deviceAlias = "device"
    private let transport: VoidbindHttpTransport

    /// Call once at app startup: inject the Swift Secure Enclave sealer, then build.
    public init(sealer: SecureEnclaveSealer = SecureEnclaveSealer(),
                transport: VoidbindHttpTransport = URLSessionHttpTransport()) {
        VoidbindIos.shared.doInit(sealer: sealer)
        self.transport = transport
    }

    // MARK: - Identity (onboarding)

    /// Mint a brand-new identity. Show `identity.recovery.format()` to the user ONCE.
    public func createIdentity() -> VoidbindUserIdentity {
        VoidbindUserIdentity.companion.create()
    }

    /// Restore from the written-down recovery secret. Throws (Kotlin
    /// `IllegalArgumentException`) on a mistyped secret — surface it as "check the secret".
    public func restoreIdentity(_ secret: String) throws -> VoidbindUserIdentity {
        VoidbindUserIdentity.companion.restore(secret: secret)
    }

    // MARK: - Device identity (hardware sign key + sealed enc key)

    /// Provision (or load) this device's key material: the Secure-Enclave-sealed
    /// Ed25519 signing key + a persisted X25519 encryption keypair.
    public func deviceIdentity() -> VoidbindDeviceIdentity {
        let store = VoidbindDeviceKeyStore.companion.getOrCreate(alias: Self.deviceAlias)
        let enc = loadOrCreateEncryptionKey()
        return VoidbindDeviceIdentity(
            signPublicKey: store.publicKey().bytes,
            encPublicKey: enc.publicKey,
            encPrivateKey: enc.privateKey,
            signFn: { message in store.sign(message: message) }
        )
    }

    /// Self-sign the first device's enrolment cert (bootstrap: user + device on one phone).
    public func enrolFirstDevice(identity: VoidbindUserIdentity, device: VoidbindDeviceIdentity) -> String {
        VoidbindEnrolment.shared.selfEnrol(
            identity: identity,
            device: device,
            issuedAt: Self.now(),
            lifetimeSeconds: VoidbindEnrolment.shared.DEFAULT_LIFETIME_SECONDS
        )
    }

    // MARK: - Coordinators (the ViewModels drive begin → SAS/audience → confirm)

    public func loginApproval(cert: String, device: VoidbindDeviceIdentity) -> VoidbindLoginApproval {
        VoidbindLoginApproval(http: transport, device: device, enrolmentCert: cert)
    }

    public func devicePairing(device: VoidbindDeviceIdentity) -> VoidbindDevicePairing {
        VoidbindDevicePairing(http: transport, device: device, pollIntervalMillis: 150)
    }

    public func deviceAuthorization(identity: VoidbindUserIdentity) -> VoidbindDeviceAuthorization {
        VoidbindDeviceAuthorization(
            http: transport,
            identity: identity,
            clock: { KotlinLong(value: Self.now()) },
            certLifetimeSeconds: VoidbindEnrolment.shared.DEFAULT_LIFETIME_SECONDS,
            pollIntervalMillis: 150
        )
    }

    /// Classify a scanned QR (login vs pairing) for the Scan screen's `switch`.
    public func parseScanned(_ uri: String) -> VoidbindVoidbindQr {
        VoidbindVoidbindQr.companion.parse(uri: uri)
    }

    // MARK: - X25519 encryption keypair persistence (Keychain, this-device-only)

    private func loadOrCreateEncryptionKey() -> VoidbindDeviceIdentityEncryptionKey {
        if let priv = keychainRead("voidbind.\(Self.deviceAlias).encpriv"),
           let pub = keychainRead("voidbind.\(Self.deviceAlias).encpub") {
            return VoidbindDeviceIdentityEncryptionKey(
                privateKey: priv.toKotlinByteArray(),
                publicKey: pub.toKotlinByteArray()
            )
        }
        let fresh = VoidbindDeviceIdentity.companion.generateEncryptionKey()
        // Hardening follow-up: seal `encpriv` under the SE key via SecureEnclaveSealer,
        // matching the Android AES-GCM wrap. Baseline here is device-only Keychain.
        keychainWrite("voidbind.\(Self.deviceAlias).encpriv", fresh.privateKey.toData())
        keychainWrite("voidbind.\(Self.deviceAlias).encpub", fresh.publicKey.toData())
        return fresh
    }

    private func keychainWrite(_ account: String, _ data: Data) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "one.rarebit.voidbind",
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(base as CFDictionary)
        var add = base
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(add as CFDictionary, nil)
    }

    private func keychainRead(_ account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "one.rarebit.voidbind",
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        var item: CFTypeRef?
        return SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess ? item as? Data : nil
    }

    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970) }
}
