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
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.dataset.DatasetAccess
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
                "WHERE tenant_id IN ({p0:String}) AND access != '' AND " +
                "((JSONType(access, {p1:String}) IN ('String') AND JSONExtractString(access, {p1:String}) IN " +
                "({p2:String})) OR (JSONType(access, {p1:String}) = 'Array' AND " +
                "hasAny(JSONExtract(access, {p1:String}, 'Array(String)'), [{p2:String}]))))",
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
    fun `each comparison should read the attribute's JSON value as a scalar or an array's elements`() {
        sql(EqualFilter(QueryField("pages"), 3L)).assert()
            .contains("JSONType(access, {p0:String}) IN ('Int64', 'UInt64') AND JSONExtractInt(access, {p0:String})")
            .contains("'Array(Int64)'")
        sql(EqualFilter(QueryField("published"), true)).assert()
            .contains("JSONExtractBool(access, {p0:String}) IN ({p1:Bool})").contains("'Array(Bool)'")
        sql(InFilter(department, listOf("sales", 2L))).assert()
            .contains("IN ({p1:String})) OR").contains("IN ({p2:Int64})) OR")
        sql(StartsWithFilter(QueryField("org"), "/acme")).assert()
            .contains("startsWith(JSONExtractString(access, {p0:String}), {p1:String})")
            .contains("arrayExists(x -> startsWith(x, {p1:String}), JSONExtract(access, {p0:String}, 'Array(String)'))")
        sql(ContainsAllFilter(QueryField("sharedWith"), listOf("bob", "carol"))).assert()
            .contains("[{p1:String}]").contains("[{p3:String}]").contains(") AND (")
        sql(IsEmptyFilter(QueryField("sharedWith"))).assert()
            .contains("(JSONType(access, {p0:String}) = 'Array' AND JSONLength(access, {p0:String}) = 0)")
        sql(IsNullFilter(department)).assert().contains("JSONType(access, {p0:String}) = 'Null'")
        sql(NorFilter(listOf(IsNotNullFilter(department)))).assert()
            .contains("NOT ifNull((JSONType(access, {p0:String}) != 'Null'), 0)")
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
        return ClickHouseDatasetAccess(dataset, "wow").compile(predicate, admitted, TABLE, SqlParameters())
    }

    private companion object {
        val BOUND: QueryDataset = QueryDataset.of(AggregateType("bi", "orders")) {
            tenant("tenant_id")
            identity("id")
            access("bi.document", "id")
            listOf("id", "tenant_id").forEach { field(it) { types(QueryValueType.STRING) } }
        }
    }
}
