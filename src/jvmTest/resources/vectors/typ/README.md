# Token-type (`typ`) golden vectors: ADR-0009

These are cross-implementation vectors for the `typ` claim that
[ADR-0009](../../../docs/adr/0009-token-type-claim.md) adds to every signed
token. voidbind-go generates them with `go test ./testvectors -run TestTypVectors
-update` and replays them through the real verifiers. Other implementations copy
the files verbatim and replay them too.

## Layout

There is one file per case, `<case>.json`. Every key in them is **test-only**,
with a deterministic seed:

```jsonc
{
  "name": "typed-cert",                       // == file stem
  "description": "…",
  "now": 1790251200,                          // unix seconds every check runs at
  "keys": { "user": { "sign_seed": "<hex>", "id": "ed25519:<hex>" }, "device": { … } },
  "tokens": { "token": "<b64url>.<b64url>", … },
  "grant_request": { "principal": "device:alpha", "resource": "space:one", "capability": "read" },
  "checks": [
    { "verifier": "possession", "token": "token", "key": "device", "cert": "cert",
      "expect": { "accept": "ok", "emit": "ok", "require": "ok" } }
  ]
}
```

- **`verifier`** is one of the following:
  - `grant`: `grant.Verify` with `key` as the only enrolled issuer and `grant_request`.
  - `cert`: `VerifyCert` against the pinned `key`.
  - `cert_user`: the `CertUser` hint.
  - `possession`: `VerifyPossession` with device `key` and the cert token labelled `cert`.
  - `op`: `VerifyOp`.
  - `op_user`: the `OpUser` hint.
- **`expect`** gives the verdict for each ADR-0009 phase. A port asserts the
  column for the phase it implements.
  - `accept` is phase 1: verify `typ` when it is present.
  - `emit` is phase 2: the same verdicts, because only minting changes.
  - `require` is phase 3: an untyped token is refused as `untyped`, except at
    `op`/`op_user`, which accept untyped v1–v3 history forever.
- **Verdicts** are `ok`, `wrong_type`, `malformed`, `untyped`, `bad_signature`,
  `expired`, `not_yet_valid` and `cert_mismatch`. Grant and cert verdicts use
  their packages' reason enums.
- **Check order.** The `typ` check runs right after the token envelope splits,
  before the signature and before any other field. A mistyped token is
  therefore `wrong_type` whatever key it is checked under. A `typ` that is not
  a JSON string (`null` included), or a case-variant key such as `"Typ"`, is
  `malformed`. A body that is not a JSON object at all is not a type question.
  It goes through the verifier's usual checks in their usual order, so a body
  corrupted in flight still fails the signature check.
- **Byte layout.** In every typed body, `typ` is the **second** member,
  immediately after `v`. Values are dotted, such as `voidbind.cert`, so no
  encoder ever escapes them.

## Cases

| case | what it pins |
|------|--------------|
| `typed-grant`, `typed-cert`, `typed-possession`, `typed-op` | each kind, typed, verifies under its own verifier, and every other verifier returns `wrong_type`. A typed cert also verifies at `op` as a genesis add |
| `overlap-grant-v1-cert-v1` | grant v1 and cert v1 share `v:1`. An untyped body with both shapes' fields is accepted by **both** verifiers today. Typed, it is accepted only as the kind its `typ` names |
| `overlap-cert-v2-possession-v2` | cert v2 and possession share `v:2`. The same attack, with one key acting as both user and device |
| `typ-malformed` | wrong case or unknown value gives `wrong_type`. Non-string or `null`, a case-variant key, or a kind at a version it lacks gives `malformed` |
| `legacy-untyped` | today's untyped tokens with their verdicts in each phase, including an untyped possession proof that the cert verifier refuses only for lacking `usr`/`dev` |

The typed membership ops, including a typed **cosigned** remove whose cosig
covers the typed core, are in `../membership/` (`typed-*`).
