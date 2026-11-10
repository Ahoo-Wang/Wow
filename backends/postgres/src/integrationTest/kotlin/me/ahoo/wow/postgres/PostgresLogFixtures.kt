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

package me.ahoo.wow.postgres

import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import java.util.UUID

/**
 * What the tests of the PostgreSQL event log share, each test class holding the tests of one of its types: the
 * stream and its context made as the TCK makes them (its `EventLogFixtures` is the TCK's own), and the statements and
 * values several of them use.
 */
abstract class PostgresLogFixtures {
    /** The context the streams of [stream] are named after, as the TCK names its own. */
    protected val context = "tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)

    /** The aggregate type of a [stream] that names none. */
    protected val type = AggregateType(context, "aggregate")

    /** A log of [context] with [partitions], as the TCK binding (`PostgresEventLogTest`) creates it. */
    protected fun createLog(context: String, partitions: Int): EventLog = PostgresFixture.log(partitions, context)

    /** A stream as the TCK makes it (`EventLogFixtures.stream`). */
    protected fun stream(
        id: String,
        version: Int,
        requestId: String = "$id-$version-${UUID.randomUUID()}",
        tenant: String = "tenant",
        commandType: String = "$context.aggregate.Act",
        scope: Scope = Scope("owner", "space"),
        causation: Causation? = null,
        headers: Map<String, String> = emptyMap(),
        aggregate: AggregateType = type,
        operator: Operator? = null,
        access: Map<String, Any?>? = null,
    ) = EventStreamRecord(
        ref = aggregate.ref(tenant, id),
        version = version,
        commandId = "command-$requestId",
        requestId = requestId,
        commandType = commandType,
        commitTime = 1_700_000_000_000L + version,
        events = listOf(
            EventRecord("$context.aggregate.Happened", 1, """{"version":$version,"text":"ü \" é"}"""),
            EventRecord("$context.aggregate.Noted", 2, """{"note":"second"}"""),
        ),
        scope = scope,
        causation = causation,
        headers = headers,
        operator = operator,
        access = access,
    )

    protected fun table(context: String, aggregate: String) = "wow_log__${context.replace('-', '_')}__$aggregate"

    protected fun execute(sql: String) {
        PostgresFixture.dataSource.connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    protected fun stream(ref: AggregateRef, version: Int) = EventStreamRecord(
        ref,
        version,
        "c-${ref.id}-$version",
        "r-${ref.id}-$version",
        "${ref.type}.Act",
        version.toLong(),
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )

    protected companion object {
        /** As the TCK waits for a tail. */
        const val TAIL_TIMEOUT_MS = 30_000L
        private const val CONTEXT_SUFFIX = 12
        const val GROUP = 10
        const val PARTITIONS = 1
    }
}
