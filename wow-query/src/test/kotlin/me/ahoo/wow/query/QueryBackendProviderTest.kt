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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.event.EventStreamQueryBackendFactory
import me.ahoo.wow.query.schema.UnavailableQueryStorageAdapter
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackendFactory
import me.ahoo.wow.tck.query.NoOpEventStreamQueryBackend
import me.ahoo.wow.tck.query.NoOpEventStreamQueryBackendFactory
import me.ahoo.wow.tck.query.NoOpSnapshotQueryBackend
import me.ahoo.wow.tck.query.NoOpSnapshotQueryBackendFactory
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class QueryBackendProviderTest {
    @Test
    fun `providers of one name merge their read models`() {
        val providers = QueryBackendProviders(
            listOf(
                QueryBackendProvider.snapshot("mongo", NoOpSnapshotQueryBackendFactory),
                QueryBackendProvider.eventStream("mongo", NoOpEventStreamQueryBackendFactory),
                QueryBackendProvider.snapshot("archive", NoOpSnapshotQueryBackendFactory),
            ),
        )

        providers.snapshots.keys.assert().containsExactlyInAnyOrder("mongo", "archive")
        providers.eventStreams.keys.assert().containsExactly("mongo")
    }

    @Test
    fun `two providers of one name cannot supply the same read model`() {
        assertThrows<IllegalArgumentException> {
            QueryBackendProviders(
                listOf(
                    QueryBackendProvider.eventStream("mongo", NoOpEventStreamQueryBackendFactory),
                    QueryBackendProvider.eventStream("mongo", NoOpEventStreamQueryBackendFactory),
                ),
            )
        }.message.assert().contains("[mongo]", "event-stream")
    }

    @Test
    fun `a provider needs a name and a read model`() {
        assertThrows<IllegalArgumentException> { SimpleQueryBackendProvider(" ", NoOpSnapshotQueryBackendFactory) }
        assertThrows<IllegalArgumentException> { SimpleQueryBackendProvider("empty") }
    }

    @Test
    fun `a custom provider serves no read model it does not override`() {
        val provider = object : QueryBackendProvider {
            override val name: String = "custom"
        }

        provider.snapshot.assert().isNull()
        provider.eventStream.assert().isNull()
    }

    @Test
    fun `backend storages locate each read model's storage through its factory`() {
        val order = MaterializedNamedAggregate("example", "order")
        val snapshotStorage = UnavailableQueryStorageAdapter("snapshot")
        val eventStorage = UnavailableQueryStorageAdapter("event")
        val snapshots = object : SnapshotQueryBackendFactory {
            override fun create(namedAggregate: NamedAggregate): QueryBackendBinding<SnapshotQueryBackend> =
                QueryBackendBinding(NoOpSnapshotQueryBackend(namedAggregate), snapshotStorage)
        }
        val eventStreams = EventStreamQueryBackendFactory {
            QueryBackendBinding(NoOpEventStreamQueryBackend(it), eventStorage)
        }

        val storages = QueryBackendStorages(snapshots, eventStreams)
        storages.storage(order, QueryModel.SNAPSHOT).assert().isSameAs(snapshotStorage)
        storages.storage(order, QueryModel.EVENT_STREAM).assert().isSameAs(eventStorage)
        storages.storage(order, QueryModel("CUSTOM")).assert().isNull()
        QueryBackendStorages(snapshots).storage(order, QueryModel.EVENT_STREAM).assert().isNull()
    }
}
