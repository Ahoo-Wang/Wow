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

package me.ahoo.wow.runtime.snapshot

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.EventCodec
import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryCheckpointStore
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryFailureLedger
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.runtime.ledger.MaintenanceSkips
import me.ahoo.wow.runtime.ledger.SkipLedger
import me.ahoo.wow.runtime.subscription.SubscribedEntry
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent.MaintenanceSkipped.Work
import me.ahoo.wow.spi.snapshot.SnapshotRebuilds
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/**
 * Deep review r1 (runtime snapshot): an operator's rebuild request (`RebuildSnapshot`, its event
 * `SnapshotRebuildRequested`) names several aggregates, and the rebuild subscription rebuilds them one after another
 * (`SnapshotMaintenance.rebuildRequested`). One whose history does not replay throws out of `recordOf` there, outside
 * the skips the in-place rebuild parks such a snapshot with (`parkedInstead`), so the request's entry fails: the ids
 * after it are not rebuilt, and the request instance of the type and tenant parks, holding back every later request
 * to it.
 */
class SnapshotRebuildRequestSkipsTest {
    data class Add(val amount: Int)
    data class Added(val amount: Int)

    class Counter {
        var total = 0
    }

    private fun model(revision: Int, poisoned: Boolean = false): AggregateModel<Counter> =
        aggregate<Counter>(CONTEXT, "counter") {
            stateRevision(revision)
            initial { Counter() }
            decide<Add>(Creation.ALLOWED) { _, body -> Decision.events(Added(body.amount)) }
            evolve<Added> {
                check(!poisoned || it.amount != POISON) { "No amount is $POISON any more." }
                total += it.amount
            }
        }

    private val log = InMemoryEventLog(2)
    private val codec = EventCodec()
    private val kernel = Kernel(log, codec)
    private val ledger = InMemoryFailureLedger()
    private val type = AggregateType(CONTEXT, "counter")

    @Test
    fun `an id of a rebuild request whose history does not replay should not keep the others from being rebuilt`():
        Unit = runBlocking {
        val store = InMemorySnapshotStore()
        // `b`'s history holds an amount the current model no longer replays; `c`'s replays.
        val (b, c) = listOf("b" to POISON, "c" to 2).map { (id, amount) ->
            val ref = type.ref("tenant", id)
            kernel.execute(model(1), Command("c-$id", "r-$id", ref, Add(amount)))
            store.save(codec.snapshotOf(model(1), ref, Counter(), 1, Scope.NONE, 0, 0))
            ref
        }
        val maintenance = SnapshotMaintenance(
            CONTEXT,
            GROUP,
            "node",
            listOf(model(2, poisoned = true)),
            SnapshotTargets.single(store),
            kernel,
            codec,
            log,
            InMemoryLeaseStore(),
            listOf(LogProgress(GROUP, log, InMemoryCheckpointStore())),
            SnapshotOptions(interval = 1.hours, leaseTtl = 2.hours, memberTtl = 2.hours),
            skips = MaintenanceSkips(
                CONTEXT,
                Work.SNAPSHOT_REWRITE,
                { SkipLedger(ledger, 2) },
                FailureClassification.DEFAULT,
            ),
        )
        // The table is claimed at revision 2 here: a request's snapshots are rewritten in place.
        maintenance.prepare()
        val request = EventStreamRecord(
            ref = AggregateType(CONTEXT, SnapshotRebuilds.AGGREGATE).ref("tenant", type.name),
            version = 1,
            commandId = "c-rebuild",
            requestId = "r-rebuild",
            commandType = "$CONTEXT.${SnapshotRebuilds.AGGREGATE}.RebuildSnapshot",
            commitTime = 0L,
            events = listOf(EventRecord(SnapshotRebuilds.SNAPSHOT_REBUILD_REQUESTED, 1, """{"ids":["b","c"]}""")),
            scope = Scope.NONE,
        )
        val failure = runCatching {
            maintenance.rebuildSubscription.handle(SubscribedEntry(request, null, emptyList()))
        }.exceptionOrNull()
        store.load(c)!!.stateRevision.assert()
            .describedAs("c's snapshot after the request naming b and c (the request's handling threw $failure)")
            .isEqualTo(2)
        store.load(b)!!.stateRevision.assert().isEqualTo(1)
    }

    private companion object {
        const val CONTEXT = "shop"
        const val GROUP = "service/shop"
        const val POISON = 13
    }
}
