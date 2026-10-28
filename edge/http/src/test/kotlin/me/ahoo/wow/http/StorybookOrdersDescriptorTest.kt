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

package me.ahoo.wow.http

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.manifest.AggregateManifest
import me.ahoo.wow.manifest.ContextManifest
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.manifest.QueryRoutes
import me.ahoo.wow.manifest.ServiceInfo
import me.ahoo.wow.manifest.StateManifest
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.backend.QueryBackendStorages
import me.ahoo.wow.query.json.QueryJson
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.policy.ScopeQueryPolicy
import me.ahoo.wow.query.schema.ManifestQuerySchemaSource
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QueryModelCompiler
import me.ahoo.wow.query.schema.QuerySchemaCatalog
import me.ahoo.wow.spi.tenant.TenantPolicy
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The storybook walkthrough's model, kept here because its descriptor is generated from it: the `orders` aggregate a
 * host integrates in `typescript/storybook/stories/view-engine/integration/`, whose `ordersDescriptor.json`
 * [StorybookOrdersDescriptorTest] writes. Owner-owned, its state a status, a warehouse, an amount and a free-text
 * remark.
 */
internal object WalkthroughOrders {
    val TYPE = AggregateType(CONTEXT, "orders")

    fun manifest(): Manifest = Manifest(
        service = ServiceInfo("shop-service"),
        contexts = listOf(
            ContextManifest(
                name = CONTEXT,
                aggregates = listOf(
                    AggregateManifest(
                        name = TYPE.name,
                        state = StateManifest(Manifest.schemaRef(STATE)),
                        queries = QueryRoutes.queries(CONTEXT, TYPE.name, snapshots = true),
                    ),
                ),
            ),
        ),
        defs = mapOf(
            STATE to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "status" to mapOf("type" to "string", "enum" to listOf("PAID", "SHIPPED", "CANCELLED")),
                    "warehouse" to mapOf("type" to listOf("string", "null")),
                    "amount" to mapOf("type" to listOf("number", "null")),
                    "remark" to mapOf(
                        "type" to listOf("string", "null"),
                        "description" to "Free text the buyer wrote; stored unindexed.",
                    ),
                ),
                "required" to listOf("status"),
            ),
        ),
    )

    private const val STATE = "OrderState"
}

/**
 * Holds `ordersDescriptor.json`, the storybook walkthrough's descriptor, to the descriptor the HTTP query route answers
 * for [WalkthroughOrders] over the in-memory query storage, written by the route's writer. Regenerate it with
 * `./gradlew :http:test --tests "*StorybookOrdersDescriptorTest" -Dwow.snapshot.update=true`.
 */
class StorybookOrdersDescriptorTest {
    @Test
    fun `the storybook walkthrough's descriptor is the one its model's query route answers`(): Unit = runBlocking {
        val manifest = WalkthroughOrders.manifest()
        val storage = InMemoryQueryStorage()
        val catalog = QuerySchemaCatalog(
            QueryBackendStorages(storage),
            QueryModelCompiler.of(listOf(ManifestQuerySchemaSource(manifest))),
        )
        val gateway = DefaultQueryGateway(
            WalkthroughOrders.TYPE,
            checkNotNull(storage.binding(WalkthroughOrders.TYPE)).backend,
            catalog.provider(WalkthroughOrders.TYPE, QueryModel.SNAPSHOT),
            policies = listOf(ScopeQueryPolicy(TenantPolicy.CALLERS)),
        )
        // As QueryHandler.describe answers the route's GET, on a node of the default list size.
        val described = gateway.describe(
            QueryEntry.HTTP,
            gateway.entryPolicy.http.listDefault(HttpQueries.DEFAULT_LIST_SIZE),
        )
        val actual = QueryJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(described) + "\n"
        if (UPDATE) {
            GOLDEN.writeText(actual)
        } else {
            actual.assert().describedAs("run with -Dwow.snapshot.update=true to rewrite $GOLDEN")
                .isEqualTo(GOLDEN.readText())
        }
    }

    private companion object {
        val GOLDEN: Path = Path.of(
            "..", "..", "typescript", "storybook", "stories", "view-engine", "integration", "ordersDescriptor.json",
        )
        val UPDATE = System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)
    }
}
