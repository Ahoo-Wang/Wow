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

package me.ahoo.wow.query.snapshot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class SnapshotStatesKtTest {
    @Test
    fun `object node state helpers should preserve object nodes`() {
        val snapshot = """{"state":{"id":"id"}}""".toJsonNode<ObjectNode>()

        snapshot.toState().path("id").textValue().assert().isEqualTo("id")
        Mono.just(snapshot).toStateDocument().block()!!.path("id").textValue().assert().isEqualTo("id")
        Flux.just(snapshot).toStateDocument().single().block()!!.path("id").textValue().assert().isEqualTo("id")
        Mono.just(PagedList(1, listOf(snapshot))).toStateDocumentPagedList().block()!!.list.single()
            .path("id").textValue().assert().isEqualTo("id")
    }

    @Test
    fun `state helper should reject non-object state`() {
        assertThrows<IllegalStateException> { """{"state":"invalid"}""".toJsonNode<ObjectNode>().toState() }
    }

    @Test
    fun `typed state helpers should project state`() {
        val snapshot = MaterializedSnapshot(
            contextName = "context",
            aggregateName = "aggregate",
            tenantId = "tenant",
            aggregateId = "id",
            version = 1,
            eventId = "event",
            firstOperator = "operator",
            operator = "operator",
            firstEventTime = 1,
            eventTime = 1,
            state = "state",
            snapshotTime = 1,
            deleted = false,
        )

        Mono.just(snapshot).toState().block().assert().isEqualTo("state")
        Flux.just(snapshot).toState().single().block().assert().isEqualTo("state")
        Mono.just(PagedList(1, listOf(snapshot))).toStatePagedList().block()!!.list.single()
            .assert().isEqualTo("state")
    }
}
