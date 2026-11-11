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

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.ContainsAllFilter
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.IsEmptyFilter
import me.ahoo.wow.api.query.IsNotNullFilter
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.MatchNoneFilter
import me.ahoo.wow.api.query.NorFilter
import me.ahoo.wow.api.query.OrFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.StartsWithFilter
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.clickhouse.query.ClickHouseQueryFixtures.TABLE
import me.ahoo.wow.clickhouse.sink.ClickHouseColumn
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.dataset.DatasetAccess
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

/**
 * The semi-join a dataset bound to an aggregate reads an access restriction as ([ClickHouseDatasetAccess]), compiled
 * against a table fixture without a server; `ClickHouseDatasetAccessTest` proves it on ClickHouse.
 */
class ClickHouseDatasetAccessSqlTest {
    private val department = QueryField("department")

    @Test
    fun `the restriction should be one semi-join on the newest record of the query's tenants`() {
        sql(InFilter(department, listOf("sales")), TenantIdFilter("t1")).assert().isEqualTo(
            "(`tenant_id`, `id`) IN (SELECT tenant_id, aggregate_id FROM `wow`.`bi_document_state` FINAL " +
                "WHERE tenant_id IN ({p0:String}) AND access != '' AND `access_department` IN ({p2:String}))",
        )
        sql(MatchAllFilter, OrFilter(listOf(TenantIdFilter("t1"), TenantIdFilter("t2"))))
            .assert().contains("WHERE tenant_id IN ({p0:String}, {p1:String}) AND access != '' AND 1)")
        sql(MatchNoneFilter, AndFilter(listOf(EqualFilter(QueryField("city"), "Hangzhou"), TenantIdFilter("t1"))))
            .assert().contains("WHERE tenant_id IN ({p0:String}) AND access != '' AND 0)")
        // A query restricted to no tenant reads the records of every tenant, by the pair.
        sql(MatchAllFilter, MatchAllFilter).assert().contains("FINAL WHERE access != '' AND 1)")
    }

    @Test
    fun `a platform-level dataset should join on the id alone, in the platform tenant`() {
        val platform = QueryDataset.of(AggregateType("bi", "orders")) {
            platform()
            identity("id")
            access("bi.document", "id")
            field("id") { types(QueryValueType.STRING) }
        }
        sql(MatchAllFilter, MatchAllFilter, platform).assert().isEqualTo(
            "`id` IN (SELECT aggregate_id FROM `wow`.`bi_document_state` FINAL " +
                "WHERE tenant_id = {p0:String} AND access != '' AND 1)",
        )
    }

    @Test
    fun `a single attribute should read its column, an operand of its type only`() {
        sql(EqualFilter(QueryField("pages"), 3L)).assert().contains("`access_pages` IN ({p1:Int64})")
        sql(EqualFilter(QueryField("published"), true)).assert().contains("`access_published` IN ({p1:Bool})")
        // An operand of another type holds of no value of it.
        sql(InFilter(department, listOf("sales", 2L))).assert().contains("`access_department` IN ({p1:String}))")
        sql(EqualFilter(department, 2L)).assert().endsWith("AND 0)")
        sql(StartsWithFilter(QueryField("org"), "/acme")).assert().contains("startsWith(`access_org`, {p1:String})")
        sql(StartsWithFilter(QueryField("pages"), "1")).assert().endsWith("AND 0)")
        // One value holds all only if they are one.
        sql(ContainsAllFilter(department, listOf("sales", "ops"))).assert()
            .contains("(`access_department` = {p1:String} AND `access_department` = {p2:String})")
        sql(ContainsAllFilter(department, listOf("sales", 2L))).assert().endsWith("AND 0)")
        sql(IsEmptyFilter(department)).assert().endsWith("AND 0)")
        sql(IsNullFilter(department)).assert().contains("`access_department` IS NULL")
        sql(NorFilter(listOf(IsNotNullFilter(department)))).assert()
            .contains("NOT ifNull((`access_department` IS NOT NULL), 0)")
    }

    @Test
    fun `a multiple attribute should read its array, its absence from the JSON value`() {
        val shared = QueryField("sharedWith")
        sql(InFilter(shared, listOf("bob", "carol"))).assert()
            .contains("hasAny(`access_sharedWith`, [{p1:String}, {p2:String}])")
        sql(ContainsAllFilter(shared, listOf("bob", "carol"))).assert()
            .contains("hasAll(`access_sharedWith`, [{p1:String}, {p2:String}])")
        val prefix = "arrayExists(x -> startsWith(x, {p1:String}), `access_sharedWith`)"
        sql(StartsWithFilter(shared, "b")).assert().contains(prefix)
        // An empty array column cannot tell an attribute a record lacks from an empty one: its JSON value can.
        sql(IsEmptyFilter(shared)).assert()
            .contains("(JSONType(access, {p0:String}) = 'Array' AND empty(`access_sharedWith`))")
        sql(IsNullFilter(shared)).assert().contains("JSONType(access, {p0:String}) = 'Null'")
        sql(IsNotNullFilter(shared)).assert().contains("JSONType(access, {p0:String}) != 'Null'")
    }

    @Test
    fun `the owner and the space, which have no column, should be read from the access JSON as strings`() {
        val owner = "JSONExtract(access, {p0:String}, 'Nullable(String)')"
        sql(EqualFilter(QueryField("owner"), "alice")).assert().contains("$owner IN ({p1:String})")
        sql(IsNullFilter(QueryField("space"))).assert().contains("$owner IS NULL")
        sql(IsNotNullFilter(QueryField("owner"))).assert().contains("$owner IS NOT NULL")
        sql(StartsWithFilter(QueryField("space"), "/a")).assert().contains("startsWith($owner, {p1:String})")
        sql(IsEmptyFilter(QueryField("owner"))).assert().endsWith("AND 0)")
    }

    @Test
    fun `an attribute the current state has no column of should be refused`() {
        assertThrows<QuerySchemaValidationException> { sql(EqualFilter(QueryField("region"), "east")) }
            .message.assert().contains("[region]").contains("access_region")
    }

    @Test
    fun `what is not an access restriction's should be refused, not read`() {
        assertThrows<QuerySchemaValidationException> { sql(GreaterThanFilter(QueryField("pages"), 1L)) }
        assertThrows<QuerySchemaValidationException> { sql(EqualFilter(QueryField("pages"), 1.5)) }
        val admitted = QueryAdmission.Trusted.list(ListQuery(MatchAllFilter), ClickHouseQueryFixtures.SCHEMA)
        // The filter compiler reads no other ELEMENT_MATCH, and none without the records of a bound aggregate.
        val compiler = ClickHouseFilterCompiler(admitted, TABLE, SqlParameters())
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(ElementMatchFilter(DatasetAccess.FIELD, EqualFilter(department, "sales")))
        }
        assertThrows<QuerySchemaValidationException> {
            compiler.compile(ElementMatchFilter(QueryField("tags"), EqualFilter(department, "sales")))
        }
    }

    @Test
    fun `a bound dataset should hold its aggregate ids in a String column`() {
        fun bound(id: String) = QueryDataset.of(AggregateType("bi", "orders")) {
            tenant("tenant_id")
            identity("id")
            access("bi.document", id)
            storage(WowJson.mapper.createObjectNode().put("table", "bi.orders"))
            listOf("id", "tenant_id").forEach {
                field(it) {
                    types(QueryValueType.STRING)
                    nullable(false)
                }
            }
            field("quantity") {
                types(QueryValueType.INTEGER)
                nullable(false)
            }
        }
        ClickHouseQueryFixtures.schema(bound("id")).storage.datasetAccess.name.assert().isEqualTo("NATIVE")
        assertThrows<QuerySchemaValidationException> { ClickHouseQueryFixtures.schema(bound("quantity")) }
            .message.assert().contains("[quantity]")
    }

    private fun sql(
        predicate: FilterExpression,
        root: FilterExpression = MatchAllFilter,
        dataset: QueryDataset = BOUND,
    ): String {
        val admitted = QueryAdmission.Trusted.list(ListQuery(root), ClickHouseQueryFixtures.SCHEMA)
        val access = ClickHouseDatasetAccess(dataset, "wow", ClickHouseTableMetadata(Duration.ofMinutes(1)) { STATE })
        runBlocking { access.load() }
        return access.compile(predicate, admitted, TABLE, SqlParameters())
    }

    private companion object {
        /** The bound aggregate's `_state`, its access columns as the Sink computes them. */
        val STATE = ClickHouseTable(
            "wow",
            "bi_document_state",
            "ReplacingMergeTree",
            listOf(
                ClickHouseColumn("tenant_id", "String"),
                ClickHouseColumn("aggregate_id", "String"),
                ClickHouseColumn("access", "String"),
                ClickHouseColumn("access_department", "Nullable(String)"),
                ClickHouseColumn("access_org", "Nullable(String)"),
                ClickHouseColumn("access_pages", "Nullable(Int64)"),
                ClickHouseColumn("access_published", "Nullable(Bool)"),
                ClickHouseColumn("access_sharedWith", "Array(String)"),
            ),
        )

        val BOUND: QueryDataset = QueryDataset.of(AggregateType("bi", "orders")) {
            tenant("tenant_id")
            identity("id")
            access("bi.document", "id")
            listOf("id", "tenant_id").forEach { field(it) { types(QueryValueType.STRING) } }
        }
    }
}
