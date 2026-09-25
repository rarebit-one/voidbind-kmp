# Recovery-wrap vectors

End-to-end vault recovery from a recovery secret alone (docs/RECOVERY-SPEC.md §5):
a space key sealed for the recovery encryption key of the `counting-entropy`
recovery vector, a change encrypted under that key, and a heyarr recovery blob
(heyarr ADR-0022 addendum) carrying the sealed key.

Sealing and encryption are randomised, so the file was minted once with
`go test ./encryption -run TestRecoveryWrapVector -update` and is only ever
**replayed** afterwards. The replay must unwrap the space key, decrypt the
change, and open the blob. `docs/recovery/reference.py selftest` checks the same
file from Python. All keys are test-only.
