package me.ahoo.wow.compensation.server.failed

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.LessThanFilter
import me.ahoo.wow.api.query.LessThanOrEqualFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.domain.ExecutionFailedState
import me.ahoo.wow.query.dsl.filter
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import tools.jackson.databind.node.LongNode

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
    fun `should build correct find next retry filter`() {
        val querySlot = slot<IListQuery>()
        val snapshotQueryGateway = mockk<SnapshotQueryGateway<ExecutionFailedState>> {
            every { list(capture(querySlot)) } returns Flux.empty()
        }
        val before = System.currentTimeMillis()
        SnapshotFindNextRetry(snapshotQueryGateway).findNextRetry(10).collectList().block()
        val after = System.currentTimeMillis()
        val nextQuery = querySlot.captured
        val actualFilter = nextQuery.filter as AndFilter
        val currentTime = (actualFilter.operands[2] as LessThanOrEqualFilter).value.asLong()

        val expectedFilter = filter {
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
                    TIMEOUT_AT_FIELD lt currentTime
                }
            }
        }

        currentTime.assert().isGreaterThanOrEqualTo(before)
        currentTime.assert().isLessThanOrEqualTo(after)
        nextQuery.limit.assert().isEqualTo(10)
        actualFilter.assert().isEqualTo(expectedFilter)
    }

    @Test
    fun `should treat timeoutAt equal to now as still executing`() {
        val snapshotQueryGateway = mockk<SnapshotQueryGateway<ExecutionFailedState>>()
        val now = 1_735_000_120_000L

        val query = SnapshotFindNextRetry(snapshotQueryGateway).nextRetryQuery(10, now)

        val retryDue = (query.filter as AndFilter).operands[3] as OrFilter
        val preparedTimedOut = retryDue.operands[1] as AndFilter
        // The command side times out only when now > timeoutAt, so the scheduler must not pick up timeoutAt == now.
        preparedTimedOut.operands[1].assert().isEqualTo(
            LessThanFilter(QueryField(TIMEOUT_AT_FIELD), LongNode.valueOf(now))
        )
    }
}
