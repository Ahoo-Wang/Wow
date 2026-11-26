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

package me.ahoo.wow.tck

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.spi.log.EventLog
import me.ahoo.wow.spi.log.EventRecord
import me.ahoo.wow.spi.log.EventStreamRecord
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.tck.log.EventLogSpec
import org.junit.jupiter.api.Test

/**
 * A backend's `refused(stream)` changes a stream's events with `withEvents`, the log spec's and the store kit's
 * alike: every other fact of the record is kept, so a field the record gains in a minor release reaches the stream the
 * backend refuses without a change to its test.
 */
class RefusedStreamTest {
    private val stream = EventStreamRecord(
        ref = AggregateType("tck", "aggregate").ref("tenant", "a-1"),
        version = 2,
        commandId = "c-2",
        requestId = "r-2",
        commandType = "tck.aggregate.Named",
        commitTime = 7,
        events = listOf(EventRecord.of("tck.aggregate.Named", 1, """{"name":"a"}""")),
        scope = Scope("alice"),
        headers = mapOf("h" to "v"),
    )
    private val refusedEvents = listOf(EventRecord.of("x.aggregate.Named", 1, """{"name":"a\u0000b"}"""))

    private val logSpec = object : EventLogSpec() {
        override fun createLog(context: String, partitions: Int): EventLog = error("Not used.")

        fun replaced(stream: EventStreamRecord) = stream.withEvents(refusedEvents)
    }

    private val storeKit = object : StoreTck() {
        override fun config(): StoreConfig = StoreConfig.of("nothing", "nothing")

        fun replaced(stream: EventStreamRecord) = stream.withEvents(refusedEvents)
    }

    @Test
    fun `a refused stream keeps every fact but its events`() {
        listOf(logSpec.replaced(stream), storeKit.replaced(stream)).forEach { refused ->
            refused.events.assert().isEqualTo(refusedEvents)
            val expected = with(stream) {
                EventStreamRecord.of(
                    ref,
                    version,
                    commandId,
                    requestId,
                    commandType,
                    commitTime,
                    refusedEvents,
                    scope,
                    causation,
                    headers,
                    operator,
                    access,
                    accessVersion,
                )
            }
            refused.assert().isEqualTo(expected)
        }
    }
}
