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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

/**
 * #6555: a state whose map values hold a polymorphic field (`Map<String, Item>`, `Item.shape` a sealed type with
 * `@JsonTypeInfo(include = EXISTING_PROPERTY)`), with the `$defs` the issue shows `manifest-tools` writing for it: each
 * subtype an `allOf` of its own object and its discriminator. Its snapshots read, and a map value's field filters by
 * its key, as a list's does.
 */
class MapOfPolymorphicValuesTest {
    private fun subtype(property: String, name: String): Map<String, Any?> = mapOf(
        "allOf" to listOf(
            mapOf(
                "type" to "object",
                "properties" to mapOf(
                    property to mapOf("type" to "integer", "format" to "int32"),
                    "type" to mapOf("type" to "string"),
                ),
                "required" to listOf(property, "type"),
            ),
            mapOf(
                "type" to "object",
                "properties" to mapOf("type" to mapOf("const" to name)),
                "required" to listOf("type"),
            ),
        ),
    )

    private val item: Map<String, Any?> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "name" to mapOf("type" to "string"),
            "shape" to mapOf("anyOf" to listOf(subtype("radius", "CIRCLE"), subtype("side", "SQUARE"))),
        ),
        "required" to listOf("name", "shape"),
    )

    private suspend fun gateway(items: Map<String, Any?>, state: String): DefaultQueryGateway {
        val storage = InMemoryQueryStorage()
        storage.save(
            SnapshotRecord(
                ref = ManifestFixtures.ORDER.ref("tenant", "b1"),
                version = 1,
                schemaFingerprint = "test",
                state = state,
                scope = Scope.NONE,
                firstEventTime = 1,
                lastEventTime = 1,
            ),
        )
        val manifest = ManifestFixtures.stateManifest(mapOf("items" to items), mapOf("Item" to item))
        val schema = ManifestFixtures.schemaOf(ManifestQuerySchemaSource(manifest))
        return DefaultQueryGateway(
            ManifestFixtures.ORDER,
            storage.binding(ManifestFixtures.ORDER).backend,
            FixedQueryModelSchemaProvider(schema),
        )
    }

    @Test
    fun `a snapshot whose map values hold a polymorphic field should read, and filter by a key's field`() {
        runTest {
            val map = mapOf("type" to "object", "additionalProperties" to mapOf("\$ref" to "#/\$defs/Item"))
            val gateway = gateway(map, """{"items":{"k1":{"name":"a","shape":{"type":"CIRCLE","radius":1}}}}""")
            gateway.list(ListQuery(MatchAllFilter, limit = 10), QueryCaller.IN_PROCESS).toList().single()
                .path("state").path("items").path("k1").path("shape").path("radius").intValue().assert().isEqualTo(1)
            listOf("CIRCLE" to 1, "SQUARE" to 0).forEach { (type, found) ->
                val filter = EqualFilter(QueryField("state.items.k1.shape.type"), type)
                gateway.list(ListQuery(filter, limit = 10), QueryCaller.IN_PROCESS).toList().size.assert()
                    .isEqualTo(found)
            }
        }
    }

    @Test
    fun `a snapshot whose list items hold the same polymorphic field should read`() {
        runTest {
            val list = mapOf("type" to "array", "items" to mapOf("\$ref" to "#/\$defs/Item"))
            gateway(list, """{"items":[{"name":"a","shape":{"type":"CIRCLE","radius":1}}]}""")
                .list(ListQuery(MatchAllFilter, limit = 10), QueryCaller.IN_PROCESS).toList().single()
                .path("state").path("items").path(0).path("name").stringValue().assert().isEqualTo("a")
        }
    }
}
