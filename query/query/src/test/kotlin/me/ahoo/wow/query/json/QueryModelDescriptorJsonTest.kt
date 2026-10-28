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

package me.ahoo.wow.query.json

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.admission.QueryBudget
import me.ahoo.wow.query.descriptor.QueryModelDescriptor
import me.ahoo.wow.query.memory.InMemoryQueryStorage
import me.ahoo.wow.query.schema.FixedQueryModelSchemaProvider
import me.ahoo.wow.query.schema.ManifestFixtures
import me.ahoo.wow.query.schema.NumericFormat
import me.ahoo.wow.query.schema.QueryModel
import me.ahoo.wow.query.schema.QuerySemanticType
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.Reference
import me.ahoo.wow.query.schema.Temporal
import me.ahoo.wow.query.schema.TimeSpan
import me.ahoo.wow.query.schema.describe
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The capability descriptor's JSON (the `GET` of the query route, decision D127), golden: the snapshot model of the
 * Manifest fixture of every query keyword, on the HTTP entry. Regenerate the golden file with
 * `./gradlew :query:test --tests "*QueryModelDescriptorJsonTest" -Dwow.snapshot.update=true`.
 */
class QueryModelDescriptorJsonTest {
    private suspend fun descriptor(budget: QueryBudget? = QueryBudget.HTTP_DEFAULT): QueryModelDescriptor =
        ManifestFixtures.orderSchema().describe(budget, DEFAULT_LIST_SIZE, ZoneId.of("UTC"))

    @Test
    fun `the descriptor of the Manifest fixture is the golden JSON`() {
        runTest {
            val actual = QueryJson.mapper.writerWithDefaultPrettyPrinter().writeValueAsString(descriptor()) + "\n"
            if (System.getProperty("wow.snapshot.update").equals("true", ignoreCase = true)) {
                GOLDEN.writeText(actual)
            }
            actual.assert().isEqualTo(GOLDEN.readText())
        }
    }

    @Test
    fun `the descriptor reads back to an equal value`() {
        runTest {
            val descriptor = descriptor()
            val json = QueryJson.mapper.writeValueAsString(descriptor)
            QueryJson.mapper.readValue(json, QueryModelDescriptor::class.java).assert().isEqualTo(descriptor)
        }
    }

    @Test
    fun `the version is a stable content hash, the ETag of the descriptor route`() {
        runTest {
            val first = descriptor()
            first.version.assert().startsWith("sha256:")
            // Recompiled from the same Manifest: the same version.
            descriptor().version.assert().isEqualTo(first.version)
            // Another budget describes other limits: another version.
            descriptor(QueryBudget("HTTP", maxListSize = 5)).version.assert().isNotEqualTo(first.version)
            descriptor(null).version.assert().isNotEqualTo(first.version)
            // A schema describes one key once.
            val schema = ManifestFixtures.orderSchema()
            schema.describe(null, null).assert().isSameAs(schema.describe(null, null))
        }
    }

    @Test
    fun `the gateway answers the descriptor of its schema under the entry's budget`() {
        runTest {
            val schema = ManifestFixtures.orderSchema()
            val gateway = DefaultQueryGateway(
                ManifestFixtures.ORDER,
                InMemoryQueryStorage().binding(ManifestFixtures.ORDER).backend,
                FixedQueryModelSchemaProvider(schema),
            )
            gateway.describe(QueryEntry.HTTP, DEFAULT_LIST_SIZE).assert()
                .isEqualTo(schema.describe(QueryBudget.HTTP_DEFAULT, DEFAULT_LIST_SIZE))
            gateway.describe(QueryEntry.IN_PROCESS).limits.maxListSize.assert().isNull()
        }
    }

    @Test
    fun `semantic types, models and value types read as 9x wrote them`() {
        val semantics = listOf(
            Temporal.Date,
            Temporal.Epoch(TimeUnit.SECONDS),
            Temporal.Formatted("yyyy-MM-dd"),
            NumericFormat.Decimal(2),
            NumericFormat.Money(currency = "CNY", scale = 2),
            NumericFormat.Money(currencyField = "currency", scale = 2),
            TimeSpan(TimeUnit.SECONDS),
            Reference(contextName = "shop", aggregateName = "member"),
        )
        semantics.forEach { semantic ->
            val json = QueryJson.mapper.writeValueAsString(semantic)
            QueryJson.mapper.readValue(json, QuerySemanticType::class.java).assert().isEqualTo(semantic)
        }
        QueryJson.mapper.writeValueAsString(Temporal.Date).assert().isEqualTo("""{"type":"temporal-date"}""")
        QueryJson.mapper.writeValueAsString(NumericFormat.Money(currency = "CNY", scale = 2))
            .assert().isEqualTo("""{"type":"money","currency":"CNY","scale":2}""")
        QueryJson.mapper.readValue("""{"type":"FUTURE","x":1}""", QuerySemanticType::class.java)
            .assert().isEqualTo(QuerySemanticType.Unknown)
        QueryJson.mapper.writeValueAsString(QueryModel.SNAPSHOT).assert().isEqualTo("\"SNAPSHOT\"")
        QueryJson.mapper.readValue("\"DECIMAL\"", QueryValueType::class.java).assert().isEqualTo(QueryValueType.DECIMAL)
    }

    private companion object {
        const val DEFAULT_LIST_SIZE = 20
        val GOLDEN: Path = Path.of("src", "test", "resources", "descriptor", "order-snapshot.descriptor.json")
    }
}
