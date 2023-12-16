package me.ahoo.wow.compensation.domain

import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import java.time.Duration

class DefaultCompensationSpecTest {
    @Test
    fun defaultValue() {
        assertThat(DefaultCompensationSpec.maxRetries, equalTo(10))
        assertThat(DefaultCompensationSpec.minBackoff, equalTo(Duration.ofSeconds(60)))
        assertThat(DefaultCompensationSpec.executionTimeout, equalTo(Duration.ofSeconds(120)))
    }

    @Test
    fun nextRetryAt0() {
        val nextRetryAt = DefaultCompensationSpec.nextRetryAt(0, 0)
        assertThat(nextRetryAt, equalTo(DefaultCompensationSpec.minBackoff.toMillis()))
    }

    @Test
    fun nextRetryAt1() {
        val nextRetryAt = DefaultCompensationSpec.nextRetryAt(1, 0)
        assertThat(nextRetryAt, equalTo(DefaultCompensationSpec.minBackoff.toMillis() * 2))
    }

    @Test
    fun nextRetryAt2() {
        val nextRetryAt = DefaultCompensationSpec.nextRetryAt(2, 0)
        assertThat(nextRetryAt, equalTo(DefaultCompensationSpec.minBackoff.toMillis() * 4))
    }

    @Test
    fun nextRetryState() {
        val retryState = DefaultCompensationSpec.nextRetryState(1, 0)
        assertThat(retryState.maxRetries, equalTo(DefaultCompensationSpec.maxRetries))
        assertThat(retryState.retries, equalTo(1))
        assertThat(retryState.retryAt, equalTo(0))
        assertThat(retryState.timoutAt, equalTo(DefaultCompensationSpec.executionTimeout.toMillis()))
        assertThat(retryState.nextRetryAt, equalTo(DefaultCompensationSpec.minBackoff.toMillis() * 2))
    }
}
