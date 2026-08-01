package me.ahoo.wow.compensation.server.failed

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.query.dsl.condition
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux

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
    fun `should build correct find next retry condition`() {
        val querySlot = slot<IListQuery>()
        val queryService = mockk<SnapshotQueryService<ExecutionFailedState>> {
            every { list(capture(querySlot)) } returns Flux.empty()
        }
        val before = System.currentTimeMillis()
        SnapshotFindNextRetry(queryService).findNextRetry(10).collectList().block()
        val after = System.currentTimeMillis()
        val nextQuery = querySlot.captured
        val currentTime = nextQuery.condition.children
            .first { it.field == NEXT_RETRY_AT_FIELD }
            .value as Long

        val originalCondition = condition {
            RECOVERABLE_FIELD isIn listOf(
                RecoverableType.RECOVERABLE.name,
                RecoverableType.UNKNOWN.name,
            )
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

        currentTime.assert().isGreaterThanOrEqualTo(before)
        currentTime.assert().isLessThanOrEqualTo(after)
        nextQuery.limit.assert().isEqualTo(10)
        nextQuery.condition.assert().isEqualTo(originalCondition)
    }
}
