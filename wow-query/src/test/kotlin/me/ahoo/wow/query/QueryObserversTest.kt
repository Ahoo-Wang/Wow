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
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.snapshot.filter.AbacPrincipalTagsRequiredException
import me.ahoo.wow.tck.mock.MOCK_AGGREGATE_METADATA
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.util.context.Context

class QueryObserversTest {
    private val aggregate: NamedAggregate = MOCK_AGGREGATE_METADATA

    @Test
    fun `a delegate that throws an Error does not stop the other delegates`() {
        val calls = mutableListOf<String>()
        val broken = object : QueryObserver {
            override val audits: Boolean = true
            override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) = TODO()
            override fun onError(namedAggregate: NamedAggregate, queryType: QueryType, error: Throwable) = TODO()
            override fun onCancel(namedAggregate: NamedAggregate, queryType: QueryType) = TODO()
            override fun onAudit(audit: QueryAudit) = TODO()
        }
        val recording = object : QueryObserver {
            override val audits: Boolean = true
            override fun onComplete(namedAggregate: NamedAggregate, queryType: QueryType) {
                calls += "complete"
            }

            override fun onError(namedAggregate: NamedAggregate, queryType: QueryType, error: Throwable) {
                calls += "error"
            }

            override fun onCancel(namedAggregate: NamedAggregate, queryType: QueryType) {
                calls += "cancel"
            }

            override fun onAudit(audit: QueryAudit) {
                calls += "audit"
            }
        }
        val composite = CompositeQueryObserver(listOf(broken, recording))

        composite.onComplete(aggregate, QueryType.LIST)
        composite.onError(aggregate, QueryType.LIST, IllegalStateException())
        composite.onCancel(aggregate, QueryType.LIST)
        composite.onAudit(
            QueryAuditTrail(aggregate, QueryType.LIST, Unit, Context.empty())
                .audit(QueryAudit.Outcome.COMPLETE)
        )

        calls.assert().containsExactly("complete", "error", "cancel", "audit")
    }

    @Test
    fun `a missing scope rejection is logged below ERROR whatever rejects it`() {
        val errors = captureErrors {
            QueryLogObserver().onError(aggregate, QueryType.LIST, AbacPrincipalTagsRequiredException())
            QueryLogObserver().onError(
                aggregate,
                QueryType.LIST,
                QueryScopeRequiredException(QueryModel.SNAPSHOT, QueryField("tenantId")),
            )
        }
        errors.assert().isEmpty()
    }

    private fun captureErrors(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block()
            appender.list.filter { it.level == Level.ERROR }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
