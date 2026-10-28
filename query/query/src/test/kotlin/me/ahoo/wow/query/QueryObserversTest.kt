/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.query

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CancellationException
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.backend.aggregate
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.error.QueryError
import me.ahoo.wow.query.error.QueryErrors
import me.ahoo.wow.query.error.QueryExecutionException
import me.ahoo.wow.query.error.QueryScopeRequiredException
import me.ahoo.wow.query.error.QueryStorageUnavailableException
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.observe.QueryAudit
import me.ahoo.wow.query.observe.QueryAuditTrail
import me.ahoo.wow.query.observe.QueryLogObserver
import me.ahoo.wow.query.observe.QueryObserver
import me.ahoo.wow.query.observe.audits
import me.ahoo.wow.query.observe.isolated
import me.ahoo.wow.query.observe.redacted
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.state.InvalidStateReadException
import me.ahoo.wow.query.state.StateNotFoundException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory

class QueryObserversTest {
    private val aggregate = AggregateType("context", "aggregate")

    @Test
    fun `a delegate that throws an Error does not stop the other delegates`() {
        val calls = mutableListOf<String>()
        val broken = object : QueryObserver {
            override val audits: Boolean = true
            override fun onComplete(aggregateType: AggregateType, queryType: QueryType) = TODO()
            override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) = TODO()
            override fun onCancel(aggregateType: AggregateType, queryType: QueryType) = TODO()
            override fun onAudit(audit: QueryAudit) = TODO()
        }
        val recording = object : QueryObserver {
            override val audits: Boolean = true
            override fun onComplete(aggregateType: AggregateType, queryType: QueryType) {
                calls += "complete"
            }

            override fun onError(aggregateType: AggregateType, queryType: QueryType, error: Throwable) {
                calls += "error"
            }

            override fun onCancel(aggregateType: AggregateType, queryType: QueryType) {
                calls += "cancel"
            }

            override fun onAudit(audit: QueryAudit) {
                calls += "audit"
            }
        }
        val composite = QueryObserver.of(listOf(broken, recording))
        composite.audits.assert().isTrue()

        composite.onComplete(aggregate, QueryType.LIST)
        composite.onError(aggregate, QueryType.LIST, IllegalStateException())
        composite.onCancel(aggregate, QueryType.LIST)
        composite.onAudit(
            QueryAuditTrail(aggregate, QueryType.LIST, Unit, QueryCaller.IN_PROCESS).audit(QueryAudit.Outcome.COMPLETE)
        )

        calls.assert().containsExactly("complete", "error", "cancel", "audit")
    }

    @Test
    fun `only the delegates that audit see an audit`() {
        val audited = mutableListOf<String>()
        fun observer(name: String, audits: Boolean) = object : QueryObserver {
            override val audits: Boolean = audits
            override fun onAudit(audit: QueryAudit) {
                audited += name
            }
        }
        val composite = QueryObserver.of(listOf(observer("quiet", false), observer("auditing", true)))
        composite.onAudit(
            QueryAuditTrail(aggregate, QueryType.COUNT, Unit, QueryCaller.IN_PROCESS).audit(QueryAudit.Outcome.ERROR)
        )
        audited.assert().containsExactly("auditing")
        QueryObserver.of(listOf(observer("quiet", false), observer("other", false))).audits.assert().isFalse()
    }

    @Test
    fun `every delegate hears the pinned events checks, one failing not stopping the others`() {
        val heard = mutableListOf<Triple<AggregateType, Int, Int>>()
        val broken = object : QueryObserver {
            override fun onEventsPinned(aggregateType: AggregateType, batched: Int, pointReads: Int) = TODO()
        }
        val recording = object : QueryObserver {
            override fun onEventsPinned(aggregateType: AggregateType, batched: Int, pointReads: Int) {
                heard += Triple(aggregateType, batched, pointReads)
            }
        }
        QueryObserver.of(listOf(broken, recording)).onEventsPinned(aggregate, 3, 1)
        QueryObserver.NONE.onEventsPinned(aggregate, 3, 1)
        heard.assert().containsExactly(Triple(aggregate, 3, 1))
    }

    @Test
    fun `none and a single observer need no composite`() {
        QueryObserver.of(emptyList()).assert().isSameAs(QueryObserver.NONE)
        val single = QueryLogObserver()
        QueryObserver.of(listOf(single)).assert().isSameAs(single)
        QueryObserver.NONE.audits.assert().isFalse()
        QueryObserver.NONE.onComplete(aggregate, QueryType.LIST)
        QueryObserver.NONE.onError(aggregate, QueryType.LIST, IllegalStateException())
        QueryObserver.NONE.onCancel(aggregate, QueryType.LIST)
        QueryObserver.NONE.onAudit(
            QueryAuditTrail(aggregate, QueryType.LIST, Unit, QueryCaller.IN_PROCESS).audit(QueryAudit.Outcome.CANCEL)
        )
    }

    @Test
    fun `a cancellation or a virtual machine error escapes the isolation`() {
        val cancelling = object : QueryObserver {
            override fun onComplete(aggregateType: AggregateType, queryType: QueryType) =
                throw CancellationException("cancelled")
        }
        assertThrows<CancellationException> { cancelling.isolated { onComplete(aggregate, QueryType.LIST) } }
        val exhausted = object : QueryObserver {
            override fun onComplete(aggregateType: AggregateType, queryType: QueryType) =
                throw OutOfMemoryError("exhausted")
        }
        assertThrows<OutOfMemoryError> { exhausted.isolated { onComplete(aggregate, QueryType.LIST) } }
    }

    @Test
    fun `a missing scope rejection is logged below ERROR whatever rejects it`() {
        val forbidden = object : RuntimeException("principal tags required"), QueryError {
            override val errorCode: String = QueryErrors.FORBIDDEN
            override val errorMsg: String = "principal tags required"
        }
        val errors = captureErrors {
            QueryLogObserver().onError(aggregate, QueryType.LIST, forbidden)
            QueryLogObserver().onError(
                aggregate,
                QueryType.LIST,
                QueryScopeRequiredException(QueryModel.SNAPSHOT, QueryField("tenantId")),
            )
            QueryLogObserver().onError(aggregate, QueryType.LIST, QueryViolation.InvalidCursor.rejection())
            QueryLogObserver().onError(
                aggregate,
                QueryType.LIST,
                QuerySchemaValidationException(QueryViolation.UnknownField(QueryField("missing"))),
            )
            // A state read the caller got wrong, or one answered as not found (review R4-Q, Q-4, Q-5).
            QueryLogObserver().onError(
                aggregate,
                QueryType.STATE,
                InvalidStateReadException("'version' is a whole number from 1: '0'.", "invalid-value", "version"),
            )
            QueryLogObserver().onError(
                aggregate,
                QueryType.TRACE,
                StateNotFoundException(aggregate.ref("t", "a-1"), StateNotFoundException.Reason.HIDDEN),
            )
        }
        errors.assert().isEmpty()
    }

    @Test
    fun `a storage that cannot be reached is logged at WARN, an outage rather than a defect`() {
        val down = IllegalStateException("Connection refused: secret-host")
        val observer = QueryLogObserver()
        val errors = captureErrors {
            observer.onError(aggregate, QueryType.LIST, QueryStorageUnavailableException(down))
        }
        errors.assert().isEmpty()
        val warnings = capture(Level.WARN) {
            observer.onError(aggregate, QueryType.LIST, QueryStorageUnavailableException(down))
            observer.onError(aggregate, QueryType.LIST, QueryStorageUnavailableException())
        }
        warnings.map { it.formattedMessage }.assert()
            .containsExactly("Query storage is unavailable; retry later.", "Query storage is unavailable; retry later.")
        warnings.first().throwableProxy.message.assert().isEqualTo(IllegalStateException::class.java.name)
        warnings.last().throwableProxy.assert().isNull()
    }

    @Test
    fun `a server fault is logged at ERROR with every cause message redacted`() {
        val secret = IllegalStateException("driver echoed secret-value", IllegalArgumentException("nested secret"))
        val errors = captureErrors {
            QueryLogObserver().onError(
                aggregate,
                QueryType.LIST,
                QueryExecutionException("Query storage failed.", secret)
            )
            QueryLogObserver().onError(aggregate, QueryType.LIST, QueryExecutionException("Backend count failed."))
            QueryLogObserver().onError(aggregate, QueryType.LIST, IllegalStateException("unexpected"))
        }
        errors.map { it.formattedMessage }.assert()
            .containsExactly("Query storage failed.", "Backend count failed.", "unexpected")
        val cause = errors.first().throwableProxy
        cause.message.assert().isEqualTo(IllegalStateException::class.java.name)
        cause.cause.message.assert().isEqualTo(IllegalArgumentException::class.java.name)
        errors.first().throwableProxy.stackTraceElementProxyArray.assert().isNotEmpty()
        errors[1].throwableProxy.assert().isNull()
        errors[2].throwableProxy.message.assert().isEqualTo("unexpected")
    }

    @Test
    fun `a redacted cause chain stops at a cycle and at its depth bound`() {
        val looping = IllegalStateException("loop")
        val self = object : RuntimeException("self") {
            override val cause: Throwable get() = this
        }
        self.redacted().cause.assert().isNull()
        var deep: Throwable = looping
        repeat(40) { deep = IllegalStateException("level $it", deep) }
        generateSequence(deep.redacted()) { it.cause }.count().assert().isEqualTo(17)
    }

    private fun captureErrors(block: () -> Unit): List<ILoggingEvent> = capture(Level.ERROR, block)

    private fun capture(level: Level, block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.filter { it.level == level }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
