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

package me.ahoo.wow.apiclient.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.CursorPage
import me.ahoo.wow.api.query.ICursorQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import org.junit.jupiter.api.Test
import org.springframework.web.service.annotation.PostExchange
import reactor.core.publisher.Mono

class SnapshotCursorQueryApiTest {
    @Test
    fun `cursor methods should use cursor resources`() {
        val methods = SnapshotCursorQueryApi::class.java.methods.associateBy { it.name }

        methods.getValue("cursor").getAnnotation(PostExchange::class.java).value.assert()
            .isEqualTo("snapshot/cursor")
        methods.getValue("dynamicCursor").getAnnotation(PostExchange::class.java).value.assert()
            .isEqualTo("snapshot/cursor")
        methods.getValue("cursorState").getAnnotation(PostExchange::class.java).value.assert()
            .isEqualTo("snapshot/cursor/state")
    }

    @Test
    fun `standard snapshot APIs should compose cursor capabilities`() {
        ReactiveSnapshotCursorQueryApi::class.java
            .isAssignableFrom(ReactiveSnapshotQueryApi::class.java).assert().isTrue()
        SynchronousSnapshotCursorQueryApi::class.java
            .isAssignableFrom(SynchronousSnapshotQueryApi::class.java).assert().isTrue()
    }

    @Test
    fun `reactive cursor API should expose typed cursor pages`() {
        val api = object : ReactiveSnapshotCursorQueryApi<State> {
            override fun cursor(query: ICursorQuery): Mono<CursorPage<MaterializedSnapshot<State>>> = Mono.empty()

            override fun dynamicCursor(query: ICursorQuery): Mono<CursorPage<Map<String, Any>>> = Mono.empty()

            override fun cursorState(query: ICursorQuery): Mono<CursorPage<State>> = Mono.empty()
        }

        api.assert().isInstanceOf(SnapshotCursorQueryApi::class.java)
    }

    @Test
    fun `synchronous cursor API should expose typed cursor pages`() {
        val api = object : SynchronousSnapshotCursorQueryApi<State> {
            override fun cursor(query: ICursorQuery): CursorPage<MaterializedSnapshot<State>> =
                CursorPage(emptyList(), null)

            override fun dynamicCursor(query: ICursorQuery): CursorPage<Map<String, Any>> =
                CursorPage(emptyList(), null)

            override fun cursorState(query: ICursorQuery): CursorPage<State> = CursorPage(emptyList(), null)
        }

        api.assert().isInstanceOf(SnapshotCursorQueryApi::class.java)
    }

    private data class State(val id: String)
}
