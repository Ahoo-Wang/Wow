package me.ahoo.wow.compensation.domain

import me.ahoo.test.asserts.assert
import me.ahoo.wow.compensation.api.RetrySpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DefaultNextRetryAtCalculatorTest {
    companion object {
        internal val testRetrySpec = RetrySpec(
            maxRetries = 10,
            minBackoff = 180,
            executionTimeout = 120
        )
    }

    @Test
    fun `should calculate next retry at given retry count 0`() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 0, 0)
        nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L)
    }

    @Test
    fun `should calculate next retry at given retry count 1`() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 1, 0)
        nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L * 2)
    }

    @Test
    fun `should calculate next retry at given retry count 2`() {
        val nextRetryAt = DefaultNextRetryAtCalculator.nextRetryAt(testRetrySpec.minBackoff, 2, 0)
        nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L * 4)
    }

    @Test
    fun `should calculate next retry state`() {
        val retryState = DefaultNextRetryAtCalculator.nextRetryState(testRetrySpec, 1, 0)
        retryState.retries.assert().isEqualTo(1)
        retryState.retryAt.assert().isEqualTo(0)
        retryState.timeoutAt.assert().isEqualTo(testRetrySpec.executionTimeout * 1000L)
        retryState.nextRetryAt.assert().isEqualTo(testRetrySpec.minBackoff * 1000L * 2)
    }

    @Test
    fun `should calculate int max execution timeout without overflowing`() {
        val retryState = DefaultNextRetryAtCalculator.nextRetryState(
            retrySpec = RetrySpec(
                maxRetries = 1,
                minBackoff = 0,
                executionTimeout = Int.MAX_VALUE
            ),
            retries = 0,
            retryAt = 0
        )

        retryState.timeoutAt.assert().isEqualTo(Int.MAX_VALUE.toLong() * 1000L)
    }

    @Test
    fun `should reject a backoff that cannot be represented as epoch milliseconds`() {
        assertThrows<ArithmeticException> {
            DefaultNextRetryAtCalculator.nextRetryAt(
                minBackoff = Int.MAX_VALUE,
                retries = 32,
                currentRetryAt = 0
            )
        }
    }

    @Test
    fun `should reject retry counts that cannot be shifted safely`() {
        assertThrows<ArithmeticException> {
            DefaultNextRetryAtCalculator.nextRetryAt(
                minBackoff = 1,
                retries = Long.SIZE_BITS - 1,
                currentRetryAt = 0
            )
        }
    }

    @Test
    fun `should reject negative retry values`() {
        assertThrows<IllegalArgumentException> {
            DefaultNextRetryAtCalculator.nextRetryAt(minBackoff = -1, retries = 0, currentRetryAt = 0)
        }
        assertThrows<IllegalArgumentException> {
            DefaultNextRetryAtCalculator.nextRetryAt(minBackoff = 1, retries = -1, currentRetryAt = 0)
        }
        assertThrows<IllegalArgumentException> {
            DefaultNextRetryAtCalculator.nextRetryState(
                retrySpec = RetrySpec(maxRetries = 1, minBackoff = 0, executionTimeout = -1),
                retries = 0,
                retryAt = 0
            )
        }
    }

    @Test
    fun `should validate the complete retry specification range`() {
        assertThrows<IllegalArgumentException> {
            DefaultNextRetryAtCalculator.validate(
                RetrySpec(maxRetries = -1, minBackoff = 0, executionTimeout = 0)
            )
        }
        assertThrows<ArithmeticException> {
            DefaultNextRetryAtCalculator.validate(
                RetrySpec(maxRetries = 32, minBackoff = Int.MAX_VALUE, executionTimeout = 0)
            )
        }
    }
}
