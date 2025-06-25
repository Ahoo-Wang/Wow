package me.ahoo.wow.compensation.server.failed

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.domain.ExecutionFailedStateProperties
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.snapshot.nestedState
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
            ExecutionFailedStateProperties.RECOVERABLE ne RecoverableType.UNRECOVERABLE.name
            ExecutionFailedStateProperties.IS_RETRYABLE eq true
            ExecutionFailedStateProperties.RETRY_STATE__NEXT_RETRY_AT lte currentTime
            or {
                ExecutionFailedStateProperties.STATUS eq ExecutionFailedStatus.FAILED.name
                and {
                    ExecutionFailedStateProperties.STATUS eq ExecutionFailedStatus.PREPARED.name
                    ExecutionFailedStateProperties.RETRY_STATE__TIMEOUT_AT lte currentTime
                }
            }
        }
        nextCondition.assert().isEqualTo(originalCondition)
    }
}
