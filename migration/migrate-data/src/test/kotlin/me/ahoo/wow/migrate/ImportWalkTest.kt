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

package me.ahoo.wow.migrate

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.kernel.memory.InMemorySnapshotStore
import me.ahoo.wow.migrate.example.NineXExample
import me.ahoo.wow.runtime.access.AccessSchema
import me.ahoo.wow.runtime.access.AccessWalking
import me.ahoo.wow.spi.access.AccessWalk
import me.ahoo.wow.spi.access.AccessWalk.Companion.IMPORTING
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An import into a service whose nodes run: it claims each type's walk once its first streams are in, so a node that
 * saw the type's store created never records them walked, and the nodes walk them unless the snapshot step completes
 * the walk.
 */
class ImportWalkTest {
    private val type = NineXExample.orderModel.type
    private val order = NineXAggregate("example-service", "order")
    private val schema = AccessSchema.of(NineXExample.orderModel)

    /** A V10 service's log, the 9.x order imported into it, and the access walks of its nodes. */
    private inner class Service {
        val log = InMemoryEventLog(4)
        val events = InMemoryNineXEventSource(NineXExample.streams())
        val checkpoints = InMemoryCheckpointRepository()
        val walked = CopyOnWriteArrayList<ObservedEvent.AccessWalked>()

        suspend fun imported(checkpoints: InMemoryCheckpointRepository = this.checkpoints) {
            EventStreamImporter(events, NineXExample.plan, { log }, checkpoints).importAll(order)
        }

        /** The snapshot step: the order's snapshots written, its history stamped, and its walk recorded. */
        suspend fun snapshots() {
            val writer = SnapshotWriter(
                events,
                NineXExample.plan,
                MigrationModels(listOf(NineXExample.orderModel, NineXExample.cartModel)),
                { log },
                { InMemorySnapshotStore() },
                checkpoints,
            )
            writer.recordWalks(listOf(writer.writeAll(order)))
        }

        /** The access walks of a node of the order's context, as it starts or is activated from standby. */
        fun node() = AccessWalking(
            type.context,
            "service/${type.context}",
            "n-1",
            listOf(NineXExample.orderModel),
            Kernel(log),
            { log },
            InMemoryLeaseStore(),
            observer = object : Observer {
                override fun observed(event: ObservedEvent) {
                    if (event is ObservedEvent.AccessWalked) walked += event
                }
            },
        )

        /** Whether each of the order's streams carries attributes. */
        suspend fun stamped(): List<Boolean> =
            log.aggregates(type, null, 10).refs.flatMap { ref -> log.read(ref).toList().map { it.access != null } }
    }

    @Test
    fun `a node that saw the store created by an import walks the streams it appended, which carry no attributes`(): Unit =
        runBlocking {
            val service = Service()
            val walking = service.node()
            walking.maintain()
            service.imported()
            service.log.walk(type).assert().isEqualTo(AccessWalk(IMPORTING, emptyMap()))
            service.stamped().assert().isNotEmpty().containsOnly(false)
            // The run after the first is not due; the third claims the walk in the import's place and walks it.
            walking.maintain()
            walking.maintain()
            service.log.walk(type)!!.let {
                it.schema.assert().isEqualTo(schema)
                it.walked.assert().isEqualTo(1)
                it.completed.assert().isTrue()
            }
            service.stamped().assert().containsOnly(true)
        }

    @Test
    fun `the import's unfinished walk is walked by a node at activation, and one the snapshot step completed is not`(): Unit =
        runBlocking {
            val walked = Service()
            walked.imported()
            walked.node().maintain().assert().isFalse()
            walked.log.walk(type)!!.let {
                it.schema.assert().isEqualTo(schema)
                it.walked.assert().isEqualTo(1)
                it.completed.assert().isTrue()
            }
            walked.walked.assert().isNotEmpty()
            walked.stamped().assert().containsOnly(true)

            val snapshotted = Service()
            snapshotted.imported()
            snapshotted.snapshots()
            snapshotted.log.walk(type).assert().isEqualTo(AccessWalk(schema, emptyMap(), walked = 1, completed = true))
            snapshotted.node().maintain().assert().isFalse()
            snapshotted.walked.assert().isEmpty()
            snapshotted.stamped().assert().containsOnly(true)
        }

    @Test
    fun `an import claims a type's walk once, and leaves one already recorded as it is`(): Unit = runBlocking {
        val service = Service()
        service.imported()
        service.log.claim(type, AccessWalk(schema, emptyMap(), completed = true), expected = IMPORTING)
            .assert().isTrue()
        // An import from its start appends the same streams again: the walk recorded stays.
        service.imported(InMemoryCheckpointRepository())
        service.log.walk(type).assert().isEqualTo(AccessWalk(schema, emptyMap(), completed = true))
    }
}
