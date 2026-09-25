package one.rarebit.cruciform.domain

import kotlin.test.assertIs

/** Shared by the engine tests: unwrap an [EngineResult], failing the test on the other branch. */
internal fun active(engine: VoidbindEngine): IdentityState.Active = assertIs(engine.identity.value)

internal fun failure(result: EngineResult<*>): EngineFailure = assertIs<EngineResult.Failed>(result).failure

internal fun <T> ready(result: EngineResult<T>): T = when (result) {
    is EngineResult.Ready -> result.value
    is EngineResult.Failed -> throw AssertionError("expected Ready, got $result")
}
