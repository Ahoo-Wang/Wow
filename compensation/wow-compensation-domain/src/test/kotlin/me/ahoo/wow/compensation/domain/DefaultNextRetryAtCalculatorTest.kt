package me.ahoo.wow.compensation.domain

import me.ahoo.wow.compensation.api.RetrySpec
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
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
        assertThat(nextRetryAt, equalTo(testRetrySpec.minBackoff * 1000L))
    }

    @Test
    fun nextRetryAt1() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 1, 0)
        assertThat(nextRetryAt, equalTo(testRetrySpec.minBackoff * 1000L * 2))
    }

    @Test
    fun nextRetryAt2() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 2, 0)
        assertThat(nextRetryAt, equalTo(testRetrySpec.minBackoff * 1000L * 4))
    }

    @Test
    fun nextRetryState() {
        val retryState = DefaultNextRetryAtCalculator.nextRetryState(testRetrySpec, 1, 0)
        assertThat(retryState.retries, equalTo(1))
        assertThat(retryState.retryAt, equalTo(0))
        assertThat(
            retryState.timoutAt,
            equalTo(testRetrySpec.executionTimeout * 1000L)
        )
        assertThat(
            retryState.nextRetryAt,
            equalTo(testRetrySpec.minBackoff * 1000L * 2)
        )
    }
}
