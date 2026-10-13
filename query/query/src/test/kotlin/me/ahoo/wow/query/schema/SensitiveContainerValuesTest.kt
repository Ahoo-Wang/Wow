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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationGroup
import me.ahoo.wow.api.query.AggregationMetric
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.CountQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode

/**
 * A sensitive value inside maps and nested collections stays protected end to end: masked in results and rejected
 * where admission protects it, never silently left raw. 9.x inferred these rules from `@Sensitive` value classes; the
 * V10 schema states them directly on the container's leaf values, as the Manifest does.
 */
class SensitiveContainerValuesTest {
    private val phone = MaskRule(SensitivityLevel.DISPLAY)
    private val idCard = MaskRule(SensitivityLevel.CONFIDENTIAL)

    private val schema = boundSchemaFixture(
        objectFixture(
            "state" to objectFixture(
                "phoneBook" to mapFixture(string(phone)),
                "groups" to arrayFixture(arrayFixture(string(phone))),
                "listBook" to mapFixture(arrayFixture(string(phone))),
                "idBook" to mapFixture(string(idCard)),
                "items" to arrayFixture(objectFixture("id" to mapFixture(string(idCard)))),
            ),
        ),
    )

    @Test
    fun `values in maps and nested lists are masked`() {
        val record = WowJson.mapper.readTree(
            """{"state":{"phoneBook":{"home":"123456"},"groups":[["123456"]],"listBook":{"work":["123456"]},
                "idBook":{"main":"ab12"},"items":[{"id":{"x":"ab12"}}]}}"""
        ) as ObjectNode
        val masked = SchemaMasker.create(schema)!!.mask(record).path("state")
        masked.path("phoneBook").path("home").stringValue().assert().isEqualTo("******")
        masked.path("groups").path(0).path(0).stringValue().assert().isEqualTo("******")
        masked.path("listBook").path("work").path(0).stringValue().assert().isEqualTo("******")
        masked.path("idBook").path("main").stringValue().assert().isEqualTo("****")
        masked.path("items").path(0).path("id").path("x").stringValue().assert().isEqualTo("****")
    }

    @Test
    fun `values in maps and nested lists are protected from comparison and aggregation`() {
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.count(CountQuery(EqualFilter(QueryField("state.idBook.main"), "ab12")), schema)
        }.violation.assert().isInstanceOf(QueryViolation.ProtectedComparison::class.java)
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.count(
                CountQuery(ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("id.x"), "ab12"))),
                schema,
            )
        }.violation.assert().isInstanceOf(QueryViolation.ProtectedComparison::class.java)
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.aggregate(
                AggregationQuery(
                    groupBy = listOf(AggregationGroup.Terms(QueryField("state.phoneBook.home"), "home")),
                    metrics = listOf(AggregationMetric.Count("count")),
                ),
                schema,
            )
        }.violation.assert().isInstanceOf(QueryViolation.ProtectedAggregation::class.java)
    }

    private fun string(rule: MaskRule) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(QueryValueType.STRING), maskRule = rule)
}
