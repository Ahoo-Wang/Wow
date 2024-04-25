package me.ahoo.wow.compensation.server.failed

import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.RetryState
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.query.condition
import me.ahoo.wow.query.nestedState
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class SnapshotFindNextRetryTest {
    companion object {
        private const val STATE_FIELD_PREFIX = "state."
        private const val STATUS_FIELD = STATE_FIELD_PREFIX + "status"
        private const val RECOVERABLE_FIELD = STATE_FIELD_PREFIX + "recoverable"
        private const val IS_RETRYABLE_FIELD = STATE_FIELD_PREFIX + "isRetryable"
        private const val RETRY_STATE_FIELD = STATE_FIELD_PREFIX + "retryState"
        private const val RETRY_STATE_FIELD_PREFIX = "$RETRY_STATE_FIELD."
        private const val NEXT_RETRY_AT_FIELD = RETRY_STATE_FIELD_PREFIX + "nextRetryAt"
        private const val TIMEOUT_AT_FIELD = RETRY_STATE_FIELD_PREFIX + "timeoutAt"
    }

    @Test
    fun testCondition() {
        val currentTime = System.currentTimeMillis()
        val originalCondition = condition {
            RECOVERABLE_FIELD ne RecoverableType.UNRECOVERABLE.name
            IS_RETRYABLE_FIELD eq true
            NEXT_RETRY_AT_FIELD lte currentTime
            or {
                STATUS_FIELD eq ExecutionFailedStatus.FAILED.name
                and {
                    STATUS_FIELD eq ExecutionFailedStatus.PREPARED.name
                    TIMEOUT_AT_FIELD lte currentTime
                }
            }
        }
        val nextCondition = condition {
            nestedState()
            ExecutionFailedState::recoverable ne RecoverableType.UNRECOVERABLE.name
            ExecutionFailedState::isRetryable eq true
            ExecutionFailedState::retryState nested {
                RetryState::nextRetryAt lte currentTime
            }
            or {
                ExecutionFailedState::status eq ExecutionFailedStatus.FAILED.name
                and {
                    ExecutionFailedState::status eq ExecutionFailedStatus.PREPARED.name
                    ExecutionFailedState::retryState nested {
                        RetryState::timeoutAt lte currentTime
                    }
                }
            }
        }
        assertThat(nextCondition, equalTo(originalCondition))
    }
}