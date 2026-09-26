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
import me.ahoo.wow.tck.query.NoOpEventStreamQueryBackendFactory
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
}
