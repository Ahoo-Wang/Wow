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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.access.AccessAttribute
import me.ahoo.wow.api.aggregate.AggregateRef
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.kernel.Kernel
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import me.ahoo.wow.spi.access.AccessStamps
import me.ahoo.wow.spi.access.AccessValues
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The built-in subscription `wow.access`: a commit that changed the attributes stamps them over the history. */
class AccessStampingTest {
    class Document(val id: String) {
        var department: String? = null
    }

    private val department = AccessAttribute.single<Document, String>("department", String::class.java)
    private val model = aggregate<Document>("office", "document") {
        initial { Document(it.id) }
        access(department) { it.department }
    }
    private val ref = model.type.ref("tenant", "d-1")

    /** The log, recording the ranges each stamp names. */
    private class Recording(val log: InMemoryEventLog = InMemoryEventLog()) : AccessStamps by log {
        val ranges = ArrayList<IntRange>()

        override suspend fun stamp(ref: AggregateRef, access: Map<String, Any?>, asOf: Int, from: Int, until: Int): Int {
            ranges += from until until
            return log.stamp(ref, access, asOf, from, until)
        }
    }

    private fun access(department: String?, owner: String = "alice") =
        AccessValues.ofStream(mapOf("department" to department), Scope(owner))

    private fun stream(version: Int, access: Map<String, Any?>?) = EventStreamRecord(
        ref = ref,
        version = version,
        commandId = "c-$version",
        requestId = "r-$version",
        commandType = "office.document.Edit",
        commitTime = version.toLong(),
        events = listOf(EventRecord("office.document.Edited", 1, "{}")),
        scope = Scope("alice"),
        access = access,
    )

    private fun stamping(log: Recording, batch: Int = AccessStampOptions.DEFAULT_BATCH) =
        AccessStamping("office", listOf(model), Kernel(log.log), { log }, AccessStampOptions(batch = batch))

    /** Appends [streams] and hands each to [stamping] as its subscription would. */
    private fun commit(log: Recording, stamping: AccessStamping, vararg streams: EventStreamRecord) = runBlocking {
        streams.forEach { stream ->
            log.log.append(stream)
            val subscription = stamping.subscription
            subscription.handle(subscription.decoder.decode(stream, null, null))
        }
    }

    private fun history(log: Recording) = runBlocking { log.log.read(ref).toList().map { it.access } }

    @Test
    fun `the stamping runs as a built-in subscription`() {
        stamping(Recording()).subscription.kind.assert().isEqualTo(FunctionKind.SYSTEM)
    }

    @Test
    fun `a commit that changed the attributes stamps them over the history, one that changed none stamps nothing`() {
        val log = Recording()
        val stamping = stamping(log)
        commit(log, stamping, stream(1, access("sales")), stream(2, access("sales")))
        log.ranges.assert().isEmpty()
        commit(log, stamping, stream(3, access("legal")))
        log.ranges.assert().containsExactly(1 until 3)
        history(log).assert().containsExactly(access("legal"), access("legal"), access("legal"))
        // A transfer changes the built-in owner: history follows it too.
        commit(log, stamping, stream(4, access("legal", owner = "bob")))
        history(log).toSet().assert().containsExactly(access("legal", owner = "bob"))
    }

    @Test
    fun `without the stream before in memory the log's is read, and a long history is stamped a batch at a time`() {
        val log = Recording()
        commit(log, stamping(log), stream(1, access("sales")), stream(2, access("sales")), stream(3, access("sales")))
        commit(log, stamping(log, batch = 2), stream(4, access("sales")), stream(5, access("hr")))
        log.ranges.assert().containsExactly(1 until 3, 3 until 5)
        history(log).toSet().assert().containsExactly(access("hr"))
    }

    @Test
    fun `a stream of another schema's attributes is followed as any, and one without them is left to the walks`() {
        val log = Recording()
        val stamping = stamping(log)
        // Imported, then stamped before the model declared its department.
        commit(log, stamping, stream(1, null), stream(2, AccessValues.ofStream(emptyMap(), Scope("alice"))))
        log.ranges.assert().containsExactly(1 until 2)
        commit(log, stamping, stream(3, access("sales")))
        log.ranges.assert().containsExactly(1 until 2, 1 until 3)
        history(log).assert().containsExactly(access("sales"), access("sales"), access("sales"))
    }

    @Test
    fun `the options refuse what cannot run, and compare by their values`() {
        listOf(
            { AccessStampOptions(batch = 0) },
            { AccessStampOptions(capacity = 0) },
            { AccessStampOptions(walkBatch = 0) },
            { AccessStampOptions(walkRate = 0) },
            { AccessStampOptions(walkShare = 0.0) },
            { AccessStampOptions(walkShare = 1.5) },
            { AccessStampOptions(interval = Duration.ZERO) },
            { AccessStampOptions(leaseTtl = 1.seconds) },
            { AccessStampOptions(memberTtl = 1.seconds) },
        ).forEach { assertThrows<IllegalArgumentException> { it() } }
        AccessStampOptions(walkRate = 7).assert().isEqualTo(AccessStampOptions(walkRate = 7))
            .hasSameHashCodeAs(AccessStampOptions(walkRate = 7))
            .isNotEqualTo(AccessStampOptions())
        AccessStampOptions(walkShare = 0.5).assert().isNotEqualTo(AccessStampOptions())
        AccessStampOptions(walkShare = 1.0).walkShare.assert().isEqualTo(1.0)
        AccessStampOptions().toString().assert()
            .contains(
                "walkBatch=100",
                "walkRate=500",
                "walkShare=${AccessStampOptions.DEFAULT_WALK_SHARE}",
                "interval=5s"
            )
    }
}
