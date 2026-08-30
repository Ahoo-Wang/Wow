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

package me.ahoo.wow.tck.query

import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.aggregateId
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.tck.event.MockDomainEventStreams.generateEventStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.opentest4j.TestAbortedException

class CursorQueryBackendSpecCompatibilityTest {
    @Test
    fun `default cursor fixtures should abort unsupported implementations`() {
        assertThrows<TestAbortedException> { DefaultSnapshotSpec().prepare() }
        val eventStream = generateEventStream(MaterializedNamedAggregate("tck", "cursor-skip").aggregateId())
        assertThrows<TestAbortedException> { DefaultEventStreamSpec().prepare(eventStream) }
    }

    @Test
    fun `default cursor capability should abort cursor cases`() {
        assertThrows<TestAbortedException> {
            DefaultSnapshotSpec().`cursor should return an empty terminal page`()
        }
        assertThrows<TestAbortedException> {
            DefaultEventStreamSpec().`cursor should return an empty terminal page`()
        }
    }

    private class DefaultSnapshotSpec : SnapshotQueryBackendSpec() {
        override fun createSnapshotQueryBackendFactory(): SnapshotQueryBackendFactory = error("Not called.")

        fun prepare() = prepareNullAndMissingCursorSnapshots("null", "missing")
    }

    private class DefaultEventStreamSpec : EventStreamQueryBackendSpec() {
        override fun createEventStore(): EventStore = error("Not called.")

        override fun createEventStreamQueryBackendFactory(): EventStreamQueryBackendFactory = error("Not called.")

        fun prepare(eventStream: DomainEventStream) =
            prepareNullAndMissingCursorEventStreams(eventStream, eventStream)
    }
}
