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

package me.ahoo.wow.elasticsearch.snapshot

import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.elasticsearch.ElasticsearchAccessPresence
import me.ahoo.wow.elasticsearch.ElasticsearchFixture
import me.ahoo.wow.elasticsearch.ElasticsearchNames
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.ObjectNode

/**
 * A snapshot's document records which access attributes it holds at every write, a rewrite included (a commit's, or
 * the access walk's): a multiple attribute emptied stays present, one the snapshot no longer holds is not.
 */
class ElasticsearchSnapshotAccessPresenceTest {
    private val type = AggregateType(ElasticsearchFixture.newContext(), "document")
    private val ref = type.ref("tenant", "d-1")

    private fun snapshot(version: Int, access: Map<String, Any?>) = SnapshotRecord(
        ref = ref,
        version = version,
        schemaFingerprint = "it",
        state = "{}",
        scope = Scope("alice"),
        firstEventTime = 0,
        lastEventTime = version.toLong(),
        access = access,
    )

    private fun presence(): Set<String>? = runBlocking {
        val index = ElasticsearchFixture.names.snapshots(type.context, type.name)
        val document = ElasticsearchFixture.client.get({ get ->
            get.index(index).id(ElasticsearchNames.documentId(ref))
        }, ObjectNode::class.java).await()
        checkNotNull(document.source()).get(ElasticsearchAccessPresence.FIELD)?.properties()?.map { it.key }?.toSet()
    }

    @Test
    fun `each write of a snapshot records the attributes it holds, an empty one among them`(): Unit = runBlocking {
        val store = ElasticsearchFixture.store().snapshots(type.context) as ElasticsearchSnapshotStore
        store.createIndex(type.name)
        store.save(snapshot(1, mapOf("department" to "sales", "sharedWith" to listOf("bob"))))
        presence().assert().isEqualTo(setOf("department", "sharedWith"))
        // Rewritten with the set emptied: still present.
        store.save(snapshot(2, mapOf("department" to "sales", "sharedWith" to emptyList<String>())))
        presence().assert().isEqualTo(setOf("department", "sharedWith"))
        // Rewritten without the department, as a snapshot whose single attribute is null.
        store.save(snapshot(3, mapOf("department" to null, "sharedWith" to emptyList<String>())))
        presence().assert().isEqualTo(setOf("sharedWith"))
    }
}
