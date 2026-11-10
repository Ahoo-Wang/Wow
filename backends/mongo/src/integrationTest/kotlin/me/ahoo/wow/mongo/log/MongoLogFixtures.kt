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

package me.ahoo.wow.mongo.log

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.LogEntry
import java.util.UUID

/**
 * What the tests of the MongoDB event log share, each test class holding the tests of one of its types: the
 * stream and its context made as the TCK makes them (its `EventLogFixtures` is the TCK's own), and the statements and
 * values several of them use.
 */
abstract class MongoLogFixtures {
    /** The context the streams of [stream] are named after, as the TCK names its own. */
    protected val context = "tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)

    /** The aggregate type of a [stream] that names none. */
    protected val type = AggregateType(context, "aggregate")

    /** A log of [context] with [partitions], as the TCK binding (`MongoEventLogTest`) creates it. */
    protected fun createLog(context: String, partitions: Int): EventLog = MongoLogFixture.log(partitions, context)

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

    /** The streams [log] tails from the start of partition 0 within the stall grace and a margin, by sequence. */
    protected suspend fun tailed(log: EventLog, count: Int): List<LogEntry> =
        withTimeout(TAIL_TIMEOUT_MS + MongoEventLog.STALL_GRACE.inWholeMilliseconds * 2) {
            log.tail(0).take(count).toList()
        }

    protected fun stream(ref: AggregateRef) = EventStreamRecord(
        ref,
        1,
        "c-${ref.id}",
        "r-${ref.id}",
        "${ref.type}.Act",
        1,
        listOf(EventRecord("${ref.type}.Acted", 1, "{}")),
        Scope.NONE,
    )

    protected fun group(context: String, vararg ids: String): List<Pair<EventStreamRecord, Nothing?>> =
        ids.map { stream(it, 1, aggregate = AggregateType(context, "order")) to null }

    protected companion object {
        /** As the TCK waits for a tail. */
        const val TAIL_TIMEOUT_MS = 30_000L
        private const val CONTEXT_SUFFIX = 12
    }
}

/** The name of [aggregate]'s log collection in [context], which every test of the log reads by. */
internal fun collection(context: String, aggregate: String) = "wow_log__${context.replace('-', '_')}__$aggregate"
