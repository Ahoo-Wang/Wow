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
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryAdmission
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.mask.SchemaMasker
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.serialization.toJsonNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

@JvmInline
@Sensitive(SensitivityLevel.DISPLAY)
value class ContainerPhone(val value: String)

@JvmInline
@Sensitive(SensitivityLevel.CONFIDENTIAL)
value class ContainerIdCard(val value: String)

/**
 * A sensitive value type inside maps and nested collections stays protected end to end: masked in results and
 * rejected where admission protects it, never silently left raw.
 */
class SensitiveContainerValuesTest {
    private val context = QuerySchemaContext(MaterializedNamedAggregate("test", "test"), QueryModel.SNAPSHOT)

    @Test
    fun `values in maps and nested lists are masked and protected`() {
        val schema = schemaOf(
            "phoneBook" to map(string(), member(Map::class.java, ContainerPhone::class.java)),
            "groups" to array(array(string()), member(List::class.java, ContainerPhone::class.java)),
            "listBook" to map(array(string()), member(Map::class.java, ContainerPhone::class.java)),
            "idBook" to map(string(), member(Map::class.java, ContainerIdCard::class.java)),
            "items" to array(
                QueryTypeFact(
                    QueryValueKind.OBJECT,
                    setOf(QueryValueType.OBJECT),
                    properties = mapOf("id" to map(string(), member(Map::class.java, ContainerIdCard::class.java))),
                ),
            ),
        )

        val masked = SchemaMasker.create(schema)!!.mask(
            """{"state":{"phoneBook":{"home":"123456"},"groups":[["123456"]],"listBook":{"work":["123456"]},
                "idBook":{"main":"ab12"},"items":[{"id":{"x":"ab12"}}]}}""".toJsonNode<ObjectNode>()
        ).path("state")
        masked.path("phoneBook").path("home").stringValue().assert().isEqualTo("******")
        masked.path("groups").path(0).path(0).stringValue().assert().isEqualTo("******")
        masked.path("listBook").path("work").path(0).stringValue().assert().isEqualTo("******")
        masked.path("idBook").path("main").stringValue().assert().isEqualTo("****")
        masked.path("items").path(0).path("id").path("x").stringValue().assert().isEqualTo("****")

        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.count(
                EqualFilter(QueryField("state.idBook.main"), JsonSerializer.valueToTree("ab12")),
                schema
            )
        }.violation.assert().isInstanceOf(QueryViolation.ProtectedComparison::class.java)
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.count(
                ElementMatchFilter(
                    QueryField("state.items"),
                    EqualFilter(QueryField("id.x"), JsonSerializer.valueToTree("ab12")),
                ),
                schema,
            )
        }.violation.assert().isInstanceOf(QueryViolation.ProtectedComparison::class.java)
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission.Trusted.aggregate(
                aggregation {
                    terms("state.phoneBook.home", "home")
                    count("count")
                },
                schema,
            )
        }.violation.assert().isInstanceOf(QueryViolation.ProtectedAggregation::class.java)
    }

    @Test
    fun `a sensitive value type in a shape that cannot be masked fails the build`() {
        val fact = QueryTypeFact(
            QueryValueKind.OBJECT,
            setOf(QueryValueType.OBJECT),
            properties = mapOf(
                "record" to QueryTypeFact(
                    QueryValueKind.OBJECT,
                    setOf(QueryValueType.OBJECT),
                    properties = mapOf("value" to string()),
                    member = member(Map::class.java, ContainerPhone::class.java, name = "record"),
                ),
            ),
        )
        assertThrows<QuerySchemaConflictException> { fact.toDeclaration(QueryField("state")) }
            .message.assert().contains("member [record]").contains(ContainerPhone::class.java.name)
    }

    private fun schemaOf(vararg properties: Pair<String, QueryTypeFact>): QueryModelSchema {
        val state = QueryTypeFact(QueryValueKind.OBJECT, setOf(QueryValueType.OBJECT), properties = properties.toMap())
        val source = InferredQuerySchemaSource(modelSource = { state }, typeResolver = { String::class.java })
        val adapter = object : QueryStorageAdapter {
            override fun facts(logicalSchema: LogicalQuerySchema): Mono<QueryStorageFacts> {
                val bound = boundSchemaFixture(logicalSchema.root)
                return Mono.just(QueryStorageFacts(bound.bindings, bound.capabilities))
            }
        }
        return DefaultQueryModelSchemaProvider(context, listOf(source), adapter).schema().block()!!
    }

    private fun member(type: Class<*>, valueType: Class<*>, name: String = "member") = QueryMemberFact(
        name = name,
        type = type,
        annotations = emptyList(),
        valueType = valueType,
    )

    private fun string() = QueryTypeFact(QueryValueKind.SCALAR, setOf(QueryValueType.STRING))

    private fun map(values: QueryTypeFact, member: QueryMemberFact? = null) = QueryTypeFact(
        QueryValueKind.OBJECT,
        setOf(QueryValueType.OBJECT),
        additionalProperties = values,
        member = member,
    )

    private fun array(items: QueryTypeFact, member: QueryMemberFact? = null) =
        QueryTypeFact(QueryValueKind.ARRAY, items = items, member = member)
}
