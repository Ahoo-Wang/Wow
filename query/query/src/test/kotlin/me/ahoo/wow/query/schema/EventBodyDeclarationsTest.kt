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

package me.ahoo.wow.query.schema

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.EventManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.memory.InMemoryEventQueryStorage
import org.junit.jupiter.api.Test

/** Which events declare each path of their bodies: a map's keys among them, past the paths it keeps. */
class EventBodyDeclarationsTest {
    private val type = AggregateType("shop", "cart")
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                "shop",
                aggregates = listOf(
                    AggregateManifest(
                        "cart",
                        state = StateManifest(Manifest.schemaRef("Cart")),
                        events = listOf(
                            EventManifest(
                                "Tagged",
                                aliases = listOf("Labelled"),
                                schema = Manifest.schemaRef("Tagged"),
                            ),
                            EventManifest("Priced", revision = 2, schema = Manifest.schemaRef("Priced")),
                        ),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            "Cart" to mapOf("type" to "object"),
            "Tagged" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "tags" to mapOf("type" to "object", "additionalProperties" to mapOf("type" to "string")),
                ),
            ),
            "Priced" to mapOf("type" to "object", "properties" to mapOf("tags" to mapOf("type" to "object"))),
        ),
    )

    private val bodies = runBlocking {
        val storage = InMemoryEventQueryStorage({ emptyList() }).binding(type).storage
        QueryModelCompiler.of(events = EventsQuerySchemas(manifest))
            .compile(QuerySchemaContext(type, QueryModel.EVENTS), storage).schema()
    }.eventBodies!!

    private fun declaring(path: String) = bodies.declaring(QueryField(path).toPathTemplate())

    @Test
    fun `a path is declared by the events whose bodies reach it, by their revision, under their stored names`() {
        val tagged = setOf("shop.cart.Tagged", "Labelled", "shop.cart.Labelled")
        declaring("tags").assert().isEqualTo(mapOf(1 to tagged, 2 to setOf("shop.cart.Priced")))
        declaring("tags.red").assert().isEqualTo(mapOf(1 to tagged))
        declaring("price").assert().isEmpty()
        bodies.events.assert().isEqualTo(QueryField("events"))
    }

    @Test
    fun `the keys of a map, past the paths it keeps, are found again`() {
        (0..EventBodyDeclarations.MAX_CACHED_PATHS).forEach { key ->
            declaring("tags.k$key").keys.assert().containsExactly(1)
        }
        declaring("tags.k0").keys.assert().containsExactly(1)
    }
}
