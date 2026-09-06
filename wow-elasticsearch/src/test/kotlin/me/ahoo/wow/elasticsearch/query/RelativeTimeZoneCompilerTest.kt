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

package me.ahoo.wow.elasticsearch.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.TodayFilter
import me.ahoo.wow.api.query.schema.QueryCapability
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.query.schema.QueryFieldBinding
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryStorageType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

class RelativeTimeZoneCompilerTest {
    private val compiler = object : AbstractElasticsearchFilterCompiler(defaultDeletionState = null) {}
    private val field = QueryField("createdAt")
    private val pattern = "yyyy-MM-dd HH:mm:ss"

    @Test
    fun `native naive datetime rejects non UTC effective zones`() {
        listOf("date", "date_nanos").forEach { kind ->
            listOf("Asia/Shanghai", "America/New_York").forEach { zone ->
                assertThrows<QuerySchemaValidationException> {
                    compiler.compile(TodayFilter(field, zoneId = zone, datePattern = pattern), schema(kind))
                }
            }
            assertThrows<QuerySchemaValidationException> {
                compiler.compile(
                    TodayFilter(
                        field,
                        zoneId = "UTC",
                        dateFormatter = DateTimeFormatter.ofPattern(pattern)
                            .withZone(ZoneId.of("Asia/Shanghai"))
                    ),
                    schema(kind),
                )
            }
            assertThrows<QuerySchemaValidationException> {
                compiler.compile(
                    TodayFilter(field, zoneId = "Asia/Shanghai", datePattern = "$pattern'X'"),
                    schema(kind)
                )
            }
        }
    }

    @Test
    fun `parser default offset is not an encoded offset`() {
        val formatter = DateTimeFormatterBuilder().appendPattern(pattern)
            .parseDefaulting(ChronoField.OFFSET_SECONDS, 28_800).toFormatter()
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(TodayFilter(field, "Asia/Shanghai", dateFormatter = formatter), schema("date"))
        }
    }

    @Test
    fun `guard follows resolved range paths and nested scope`() {
        val schema = schema("date", QueryField("state.orders.createdAt"), QueryField("orders.createdAt.native"))
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                TodayFilter(QueryField("orders.createdAt.native"), "Asia/Shanghai", datePattern = pattern),
                schema
            )
        }
        assertThrows<QuerySchemaValidationException> {
            compiler.compileScoped(
                TodayFilter(QueryField("createdAt.native"), "Asia/Shanghai", datePattern = pattern),
                schema,
                QueryField("state.orders"),
                QueryField("orders"),
                QueryField("orders")
            )
        }
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(
                ElementMatchFilter(
                    QueryField("state.orders"),
                    AndFilter(listOf(TodayFilter(QueryField("createdAt"), "Asia/Shanghai", datePattern = pattern)))
                ),
                schema
            )
        }
    }

    @Test
    fun `UTC encoded zones calendar labels numeric and physical controls remain supported`() {
        listOf("UTC", "GMT", "+00:00").forEach { zone ->
            compiler.compile(TodayFilter(field, zoneId = zone, datePattern = pattern), schema("date"))
                .isBool.assert().isTrue()
        }
        listOf("yyyy-MM-dd", "${pattern}XXX", "$pattern VV").forEach { format ->
            compiler.compile(TodayFilter(field, "Asia/Shanghai", datePattern = format), schema("date"))
                .isBool.assert().isTrue()
        }
        compiler.compile(
            TodayFilter(field, "Asia/Shanghai", dateFormatter = DateTimeFormatter.ISO_INSTANT),
            schema("date")
        )
            .isBool.assert().isTrue()
        val utcFormatter = DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC)
        compiler.compile(TodayFilter(field, "Asia/Shanghai", dateFormatter = utcFormatter), schema("date"))
            .isBool.assert().isTrue()
        compiler.compile(TodayFilter(field, "Asia/Shanghai"), schema("date")).isBool.assert().isTrue()
        compiler.compile(TodayFilter(field, "Asia/Shanghai", datePattern = pattern), schema("keyword"))
            .isBool.assert().isTrue()
        compiler.compilePhysical(TodayFilter(field, "Asia/Shanghai", datePattern = pattern)).isBool.assert().isTrue()
    }

    private fun schema(kind: String, logical: QueryField = field, resolved: QueryField = field) = QueryModelSchema(
        QueryModel.SNAPSHOT,
        emptySet(),
        mapOf(
            logical to QueryFieldSchema(
                title = null, description = null, enumValues = null, valueTypes = setOf(QueryValueType.STRING),
                nullable = false, required = true, cardinality = QueryCardinality.SINGLE,
                semanticType = Temporal.Formatted(pattern), dynamicChildren = false,
                bindings = mapOf(QueryCapability.RANGE to QueryFieldBinding(resolved, resolved, QueryStorageType(kind))),
                rewriteMode = QueryRewriteMode.NONE,
            )
        ),
    )
}
