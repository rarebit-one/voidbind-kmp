package one.rarebit.cruciform.pairing

/**
 * Something that keeps the process alive (and the user informed) while an invite is
 * waiting on the relay for the new device — on Android, a foreground service with a
 * "Waiting for the new device to join…" notification ([ServiceKeepAlive]). A seam so
 * the [InviteCoordinator] state machine is pure Kotlin and unit-tested on the JVM.
 * [begin] / [end] are idempotent and always paired by the coordinator.
 */
interface ProcessKeepAlive {
    fun begin()
    fun end()

    /** No-op: previews and tests. */
    object None : ProcessKeepAlive {
        override fun begin() {}
        override fun end() {}
    }
}
