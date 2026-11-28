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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.mask.PayloadMasks
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.spi.json.WowJson
import me.ahoo.wow.spi.snapshot.SnapshotRecord
import org.junit.jupiter.api.Test

/**
 * A state property the node's schema does not declare (a newer node's state, written during a rolling update) is
 * not answered raw by a masked read: the node cannot tell whether it is sensitive (#7243). It is removed, from a state
 * read and from a snapshot query's records alike.
 */
class UndeclaredStatePropertiesTest {
    private val type = ManifestFixtures.ORDER
    private val manifest = Manifest(
        contexts = listOf(
            ContextManifest(
                type.context,
                aggregates = listOf(AggregateManifest(type.name, state = StateManifest(Manifest.schemaRef("S")))),
            ),
        ),
        defs = mapOf(
            "S" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "phone" to mapOf(
                        "type" to "string",
                        "x-wow-sensitive" to mapOf("level" to "confidential"),
                    ),
                    "sku" to mapOf("type" to "string"),
                ),
            ),
        ),
    )
    private val stored = """{"phone":"13812345678","sku":"s","card":"s3cr3t-undeclared","nested":{"pin":"s3cr3t-pin"}}"""

    private fun gateway(): DefaultQueryGateway {
        val storage = InMemoryQueryStorage()
        runBlocking {
            storage.save(
                SnapshotRecord(
                    ref = type.ref("tenant", "s1"),
                    version = 1,
                    schemaFingerprint = "test",
                    state = stored,
                    scope = Scope.NONE,
                    firstEventTime = 1,
                    lastEventTime = 1,
                ),
            )
        }
        return DefaultQueryGateway(
            type,
            storage.binding(type).backend,
            FixedQueryModelSchemaProvider(
                runBlocking { ManifestFixtures.schemaOf(ManifestQuerySchemaSource(manifest)) }
            ),
        )
    }

    @Test
    fun `a masked state read should not answer a property the schema does not declare raw`() {
        val read = PayloadMasks(manifest).state(type, WowJson.mapper.readTree(stored)).toString()
        read.assert().doesNotContain("s3cr3t-undeclared").doesNotContain("s3cr3t-pin")
    }

    @Test
    fun `a snapshot query should not answer a property the schema does not declare raw`(): Unit = runBlocking {
        listOf(QueryEntry.IN_PROCESS, QueryEntry.HTTP).forEach { entry ->
            val caller = QueryCaller(Caller(tenantId = "tenant"), entry = entry)
            gateway().list(ListQuery(MatchAllFilter, limit = 1), caller).toList().single().path("state").toString()
                .assert().describedAs(entry.toString()).doesNotContain("s3cr3t-undeclared").doesNotContain("s3cr3t-pin")
        }
    }
}
