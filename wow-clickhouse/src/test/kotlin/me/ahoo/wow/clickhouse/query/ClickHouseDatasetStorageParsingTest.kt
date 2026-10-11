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

package me.ahoo.wow.clickhouse.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryEntry
import me.ahoo.wow.query.schema.QueryDataset
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.serialization.toObjectNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration

class ClickHouseDatasetStorageParsingTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "String|STRING|false",
            "LowCardinality(Nullable(String))|STRING|true",
            "Enum8('A' = 1)|ENUM|false",
            "UInt64|INTEGER|false",
            "Nullable(Float64)|FLOAT|true",
            "Decimal(18, 2)|DECIMAL|false",
            "Bool|BOOLEAN|false",
            "Date32|DATE|false",
            "DateTime64(3, 'UTC')|DATE_TIME|false",
            "Array(String)|UNSUPPORTED|false",
        ],
    )
    fun `a column's family and nullability come from its type`(
        type: String,
        family: ClickHouseTypeFamily,
        nullable: Boolean
    ) {
        val column = ClickHouseColumn("c", type)
        column.family.assert().isEqualTo(family)
        column.nullable.assert().isEqualTo(nullable)
    }

    @Test
    fun `a date binds as epoch milliseconds and a string-like column as text`() {
        ClickHouseColumn("c", "DateTime64(3, 'UTC')").parameterType.assert().isEqualTo("Int64")
        ClickHouseColumn("c", "Enum8('A' = 1)").parameterType.assert().isEqualTo("String")
        ClickHouseColumn("c", "Nullable(Decimal(18, 2))").parameterType.assert().isEqualTo("Decimal(18, 2)")
    }

    @Test
    fun `only engines that merge rows support FINAL`() {
        fun table(engine: String) = ClickHouseTable("db", "t", engine, emptyMap())
        table("ReplicatedReplacingMergeTree").supportsFinal.assert().isTrue()
        table("CollapsingMergeTree").supportsFinal.assert().isTrue()
        table("MergeTree").supportsFinal.assert().isFalse()
        table("View").supportsFinal.assert().isFalse()
        table("t").qualifiedName.assert().isEqualTo("`db`.`t`")
        quoteIdentifier("a`b").assert().isEqualTo("`a\\`b`")
    }

    @Test
    fun `the storage binding names a table and how to deduplicate`() {
        ClickHouseDatasetStorage.of(dataset("""{"table":"bi.orders","deduplicate":"FINAL"}"""), "default")
            .assert().isEqualTo(ClickHouseDatasetStorage("bi", "orders", ClickHouseDatasetStorage.Deduplicate.FINAL))
        ClickHouseDatasetStorage.of(dataset("""{"table":"orders"}"""), "analytics")
            .assert().isEqualTo(ClickHouseDatasetStorage("analytics", "orders"))
        listOf(
            """{}""",
            """{"table":""}""",
            """{"table":"a.b.c"}""",
            """{"table":"a."}""",
            """{"table":"orders","deduplicate":"LATEST"}""",
            """{"table":"orders","engine":"x"}""",
        ).forEach { storage ->
            assertThrows<QuerySchemaValidationException>(
                storage
            ) { ClickHouseDatasetStorage.of(dataset(storage), "default") }
        }
    }

    @Test
    fun `limits become server settings that fail an overflowing query`() {
        ClickHouseQueryLimits.NONE.settings.assert().isEmpty()
        ClickHouseQueryLimits(
            maxExecutionTime = Duration.ofMillis(10),
            maxRowsToRead = 5,
            maxResultRows = 6,
            maxMemoryUsage = 7
        )
            .settings.assert().containsEntry("max_execution_time", "1")
            .containsEntry("max_rows_to_read", "5")
            .containsEntry("max_result_rows", "6")
            .containsEntry("max_memory_usage", "7")
            .containsEntry("timeout_overflow_mode", "throw")
            .containsEntry("read_overflow_mode", "throw")
            .containsEntry("result_overflow_mode", "throw")
        val options = ClickHouseDatasetOptions()
        options.limits(QueryEntry.HTTP).maxExecutionTime.assert().isEqualTo(Duration.ofSeconds(30))
        options.limits(QueryEntry.IN_PROCESS).assert().isEqualTo(ClickHouseQueryLimits.NONE)
    }

    @Test
    fun `sql constants and time zones are rendered from validated values`() {
        1.5.sqlLiteral().assert().isEqualTo("toFloat64(1.5)")
        assertThrows<IllegalStateException> { Double.NaN.sqlLiteral() }
        "Asia/Shanghai".sqlTimeZone().assert().isEqualTo("'Asia/Shanghai'")
        val parameters = SqlParameters()
        parameters.bind("x", "String").assert().isEqualTo("{p0:String}")
        parameters.bind(1, "Int64").assert().isEqualTo("{p1:Int64}")
        parameters.statement("SELECT 1").parameters.assert().containsEntry("p0", "x").containsEntry("p1", 1)
    }

    private fun dataset(storage: String) = QueryDataset.of(MaterializedNamedAggregate("tck", "orders")) {
        tenant("tenant_id")
        field("tenant_id") { types(QueryValueType.STRING) }
        storage(storage.toObjectNode())
    }
}
