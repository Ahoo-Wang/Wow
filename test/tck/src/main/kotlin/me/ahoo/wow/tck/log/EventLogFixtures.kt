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

package me.ahoo.wow.tck.log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.decide.Causation
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.spi.log.AppendOutcome
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.log.Partitioner
import me.ahoo.wow.spi.log.SnapshotTail
import me.ahoo.wow.spi.log.SnapshotTailReader
import me.ahoo.wow.spi.log.readSnapshotTailApart
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import me.ahoo.wow.spi.snapshot.SnapshotStore
import me.ahoo.wow.tck.ExactJson
import java.util.UUID

/**
 * The fixtures and hooks of the [EventLog] contract ([EventLogSpec], the class a backend's test extends): the backend's
 * hooks ([createLog], [refused], [opensOneStorePerContext], [createSnapshots], [snapshotTailsOf]), the streams a test
 * appends ([stream]) and the suite's constants.
 *
 * The contract's cases come in families, one class each: [EventLogRecordCases], [EventLogAccessCases],
 * [EventLogEntryCases], [EventLogReadCases] and [EventLogBatchCases], then [EventLogSpec] with the uniqueness and
 * concurrency cases. They form one chain, in that order, each extending the one before, because a class has one
 * superclass and JUnit runs the cases a test class inherits. The order means nothing: every family reads only this
 * class's fixtures, hooks and constants, never a member of the family it extends, so any order would run the same
 * cases.
 *
 * The suite's constants sit here, not on [EventLogSpec] as in the query contracts: the stores' own tests read
 * [TAIL_TIMEOUT_MS] unqualified, so its companion is protected, and a parent class cannot read a protected member of
 * its subclass's companion. What only the families read in it is internal.
 */
abstract class EventLogFixtures internal constructor() {
    /** A new, empty log of bounded context [context] with [partitions] partitions. */
    protected abstract fun createLog(context: String, partitions: Int): EventLog

    /** Each test gets its own context, so a backend that keeps a log per context isolates the tests. */
    internal val context = "tck_" + UUID.randomUUID().toString().replace("-", "").take(CONTEXT_SUFFIX)
    internal val type = AggregateType(context, "aggregate")
    internal val other = AggregateType(context, "other")

    internal fun createLog(partitions: Int = DEFAULT_PARTITIONS): EventLog = createLog(context, partitions)

    /**
     * [stream] changed so that this backend refuses it ([AppendOutcome.Rejected]): a value it cannot store. Null, the
     * default, for a backend that stores every stream, which skips the cases of a refused stream.
     */
    protected open fun refused(stream: EventStreamRecord): EventStreamRecord? = null

    /**
     * Whether [createLog] called again with one context opens the same log, as another node of the service does. False,
     * the default, for a backend whose every log is a store of its own, which skips the cases of two writers.
     */
    protected open val opensOneStorePerContext: Boolean = false

    /**
     * A new, empty snapshot store of bounded context [context] that this backend's log reads with its tails:
     * one in the log's database where the backend has one. Null, the default, skips those cases.
     */
    protected open fun createSnapshots(context: String): SnapshotStore? = null

    /**
     * The one-round-trip read of [snapshots], a store of [createSnapshots], with [log]'s tails, as the backend's store
     * offers it ([me.ahoo.wow.spi.store.Store.snapshotTails]); null, the default, reads them apart.
     */
    protected open fun snapshotTailsOf(log: EventLog, snapshots: SnapshotStore): SnapshotTailReader? = null

    /** What a cache miss reads: through [snapshotTailsOf] when the backend has it, else apart, as the kernel does. */
    internal suspend fun EventLog.snapshotTail(
        ref: AggregateRef,
        snapshots: SnapshotStore,
        earlier: Int
    ): SnapshotTail {
        val reader = snapshotTailsOf(this, snapshots) ?: return readSnapshotTailApart(ref, snapshots, earlier)
        return reader.readWithSnapshot(ref, earlier)
    }

    internal fun snapshot(id: String, version: Int) = SnapshotRecord(
        ref = type.ref("tenant", id),
        version = version,
        schemaFingerprint = "fingerprint",
        state = """{"id":"$id","version":$version}""",
        scope = Scope("owner", "space"),
        deleted = false,
        firstEventTime = 1_700_000_000_001L,
        lastEventTime = 1_700_000_000_000L + version,
        access = mapOf("tier" to "gold", "teams" to listOf("a", "b")),
        operator = Operator("bob", "tenant"),
        schedules = mapOf("remind" to ScheduleRule.After(60_000)),
    )

    /**
     * These sequences increase from after [floor]: a partition's sequences only increase, and a backend may leave some
     * unused.
     */
    internal fun List<Long>.assertIncreasingAfter(floor: Long) {
        zipWithNext().all { (earlier, later) -> later > earlier }.assert().describedAs("$this increase").isTrue()
        (first() > floor).assert().describedAs("$this start after $floor").isTrue()
    }

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

    /** Asserts this is [expected], comparing payloads as JSON: a backend may store them as JSON and reformat them. */
    internal fun EventStreamRecord.assertSameAs(expected: EventStreamRecord) {
        events.map { ExactJson.of(it.payload) }.assert().isEqualTo(expected.events.map { ExactJson.of(it.payload) })
        copyWith(events = expected.events).assert().isEqualTo(expected)
    }

    /** Distinct aggregate ids that land in partition 0 of [partitions]. */
    internal fun idsInPartitionZero(count: Int, partitions: Int, aggregate: AggregateType = type): List<String> =
        generateSequence(1) { it + 1 }.map { "g-$it" }
            .filter { Partitioner.partitionOf(aggregate.ref("tenant", it), partitions) == 0 }
            .take(count)
            .toList()

    protected companion object {
        internal const val DEFAULT_PARTITIONS = 8

        /** The streams of one aggregate a long read's appends commit at a time. */
        internal const val APPEND_GROUP = 64
        internal const val CONTEXT_SUFFIX = 12
        const val TAIL_TIMEOUT_MS = 30_000L
    }
}
