package me.ahoo.wow.compensation.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.compensation.api.RetrySpec
import org.junit.jupiter.api.Test

class DefaultNextRetryAtCalculatorTest {
    companion object {
        internal val testRetrySpec = RetrySpec(
            maxRetries = 10,
            minBackoff = 180,
            executionTimeout = 120
        )
    }

    @Test
    fun nextRetryAt0() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 0, 0)
        nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L)
    }

    @Test
    fun nextRetryAt1() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 1, 0)
        nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L * 2)
    }

    @Test
    fun nextRetryAt2() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 2, 0)
        nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L * 4)
    }

    @Test
    fun nextRetryState() {
        val retryState = DefaultNextRetryAtCalculator.nextRetryState(testRetrySpec, 1, 0)
        retryState.retries.assert().isEqualTo(1)
        retryState.retryAt.assert().isEqualTo(0)
        retryState.timeoutAt.assert().isEqualTo(testRetrySpec.executionTimeout * 1000L)
        retryState.nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L * 2)
    }
}
