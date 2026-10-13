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

package me.ahoo.wow.query.index

import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import org.junit.jupiter.api.Test

class QueryIndexesTest {
    @Test
    fun `declarations are read from the state schema of the Manifest`() {
        fun manifest(state: Map<String, Any?>) = Manifest(
            contexts = listOf(
                ContextManifest(
                    "shop",
                    aggregates = listOf(AggregateManifest("order", state = StateManifest("#/\$defs/Order")))
                )
            ),
            defs = mapOf("Order" to state),
        )
        val declared = manifest(
            mapOf(
                QueryIndexDeclaration.KEYWORD to listOf(
                    mapOf("name" to "ix_order_state_name", "keys" to listOf("state.name", "ownerId")),
                ),
            ),
        )
        QueryIndexDeclaration.of(declared, "shop", "order").assert()
            .containsExactly(QueryIndexDeclaration("ix_order_state_name", listOf("state.name", "ownerId")))
        QueryIndexDeclaration.of(declared, "shop", "cart").assert().isEmpty()
        QueryIndexDeclaration.of(declared, "other", "order").assert().isEmpty()
        QueryIndexDeclaration.of(manifest(emptyMap()), "shop", "order").assert().isEmpty()
        QueryIndexDeclaration.of(Manifest(contexts = declared.contexts), "shop", "order").assert().isEmpty()
        listOf<Any>(
            "ix",
            listOf("ix"),
            listOf(mapOf("name" to "ix")),
            listOf(mapOf("name" to "ix", "keys" to listOf(1))),
            listOf(mapOf("name" to 1, "keys" to listOf("state.name"))),
        ).forEach { malformed ->
            assertThrownBy<IllegalArgumentException> {
                QueryIndexDeclaration.of(manifest(mapOf(QueryIndexDeclaration.KEYWORD to malformed)), "shop", "order")
            }
        }
    }

    @Test
    fun `a declaration is named and has a key`() {
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("", listOf("state.name")) }
        assertThrownBy<IllegalArgumentException> { QueryIndexDeclaration("ix", emptyList()) }
    }

    @Test
    fun `the index mode is read from its setting`() {
        QueryIndexDdl.of("AUTO").assert().isEqualTo(QueryIndexDdl.AUTO)
        QueryIndexDdl.of("validate").assert().isEqualTo(QueryIndexDdl.VALIDATE)
        QueryIndexDdl.of("none").assert().isEqualTo(QueryIndexDdl.NONE)
        assertThrownBy<IllegalArgumentException> { QueryIndexDdl.of("create") }
    }
}
