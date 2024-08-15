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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.SimpleDynamicDocument
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.kotlin.core.publisher.toMono
import reactor.kotlin.test.test

class SnapshotStatesKtTest {

    @Test
    fun toState() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "state"
        }
        snapshot.toMono().toState().test().expectNext("state").verifyComplete()
    }

    @Test
    fun dyToState() {
        val snapshot = SimpleDynamicDocument(mapOf("state" to SimpleDynamicDocument(mapOf("id" to "id"))))
        snapshot.toMono().toStateDocument().test().consumeNextWith {
            assertThat(it.getValue<String>("id"), equalTo("id"))
        }.verifyComplete()
    }

    @Test
    fun fluxToState() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "state"
        }
        Flux.just(snapshot).toState().test().expectNext("state").verifyComplete()
    }

    @Test
    fun dyFluxToState() {
        val snapshot = SimpleDynamicDocument(mapOf("state" to SimpleDynamicDocument(mapOf("id" to "id"))))
        Flux.just(snapshot).toStateDocument().test().consumeNextWith {
            assertThat(it.getValue<String>("id"), equalTo("id"))
        }.verifyComplete()
    }

    @Test
    fun toStatePagedList() {
        val snapshot = mockk<MaterializedSnapshot<String>> {
            every { state } returns "state"
        }
        val pagedList = PagedList(1, listOf(snapshot))
        Mono.just(pagedList).toStatePagedList().test()
            .expectNextCount(1)
            .verifyComplete()
    }

    @Test
    fun dyToStatePagedList() {
        val snapshot = SimpleDynamicDocument(
            mapOf("state" to SimpleDynamicDocument(mapOf("id" to "id")))
        ) as DynamicDocument
        val pagedList = PagedList(1, listOf(snapshot))
        Mono.just(pagedList).toStateDocumentPagedList().test()
            .expectNextCount(1)
            .verifyComplete()
    }
}
