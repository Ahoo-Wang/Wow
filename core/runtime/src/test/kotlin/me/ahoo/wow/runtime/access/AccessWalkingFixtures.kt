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

package me.ahoo.wow.runtime.access

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.kernel.memory.InMemoryLeaseStore
import me.ahoo.wow.spi.access.AccessStamp
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.access.AccessWalks
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.observation.ObservedEvent
import me.ahoo.wow.spi.observation.Observer
import me.ahoo.wow.spi.subscription.LeaseStore
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * A document type with a department attribute, its history appended without attributes, and the walks over it: what
 * the access walks' tests share, each test with its own.
 */
abstract class AccessWalkingFixtures {
    class Document(val id: String) {
        var department: String? = null
    }

    data class Move(val department: String)

    data class Moved(val department: String)

    protected val department = AccessAttribute.single<Document, String>("department", String::class.java)
    protected val model = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        decide<Move>(Creation.ALLOWED) { _, command -> Decision.events(Moved(command.department)) }
        evolve<Moved> { department = it.department }
        access(department) { it.department }
    }
    protected val type = model.type
    protected val options = AccessStampOptions(walkBatch = 2, walkRate = 1_000_000, interval = 1.seconds)
    protected val observed = ArrayList<ObservedEvent>()
    protected val observer = object : Observer {
        override fun observed(event: ObservedEvent) {
            observed += event
        }
    }

    protected fun access(department: String) = AccessValues.ofStream(mapOf("department" to department), Scope("alice"))

    /** Appends to [log] the streams of [ids], each moved to [departments] in turn, without attributes. */
    protected fun unstamped(log: InMemoryEventLog, ids: List<String>, vararg departments: String) = runBlocking {
        val written = InMemoryEventLog()
        val kernel = Kernel(written)
        ids.forEach { id ->
            val ref = type.ref("tenant", id)
            departments.forEachIndexed { index, moved ->
                kernel.execute(model, Command("c-$id-$index", "r-$id-$index", ref, Move(moved), scope = Scope("alice")))
            }
            written.read(ref).toList().forEach { log.append(it.copyWith(access = null)) }
        }
    }

    protected fun history(log: InMemoryEventLog, id: String) =
        runBlocking { log.read(type.ref("tenant", id)).toList().map { it.access } }

    protected fun walking(log: InMemoryEventLog, leases: InMemoryLeaseStore = InMemoryLeaseStore()) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(model),
        Kernel(log),
        { log },
        leases,
        options,
        observer = observer,
    )

    protected fun walkingOf(
        log: EventLog,
        leases: LeaseStore = InMemoryLeaseStore(),
        options: AccessStampOptions = this.options,
    ) = AccessWalking(
        "office",
        "service/office",
        "n-1",
        listOf(model),
        Kernel(log),
        { log },
        leases,
        options,
        observer = observer,
    )

    /** [log], recording the reads and stamps the walk makes, in order, by aggregate id. */
    protected class Recording(val log: InMemoryEventLog) :
        EventLog by log,
        AccessWalks by log {
        val calls = CopyOnWriteArrayList<String>()

        override fun read(ref: AggregateRef, fromVersion: Int, toVersion: Int): Flow<EventStreamRecord> {
            calls += "read ${ref.id}"
            return log.read(ref, fromVersion, toVersion)
        }

        override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
            calls += "stamp ${ref.id}"
            return log.stamp(ref, access, asOf, from, until)
        }

        override suspend fun stampAll(stamps: List<AccessStamp>): Int {
            calls += "stampAll"
            return log.stampAll(stamps)
        }
    }
}
