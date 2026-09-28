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
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.annotation.QueryDuration
import me.ahoo.wow.api.query.annotation.QueryReference
import me.ahoo.wow.api.query.annotation.QueryTemporal
import me.ahoo.wow.api.query.descriptor.FieldDescriptor
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Reference
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.api.query.schema.TimeSpan
import me.ahoo.wow.query.QueryBudget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class SemanticTypeSchemaTest {
    private val seconds = TimeSpan(TimeUnit.SECONDS)
    private val member = Reference(contextName = "example", aggregateName = "member")
    private val byFields = Reference(contextNameField = "contextName", aggregateNameField = "aggregateName")

    private fun scalar(type: QueryValueType, semantic: QuerySemanticType?) =
        QueryValueSchema(QueryValueKind.SCALAR, valueTypes = setOf(type), nullable = false, semanticType = semantic)

    private fun schemaOf(vararg properties: Pair<String, QueryValueSchema>) =
        LogicalQuerySchema(QueryValueSchema(QueryValueKind.OBJECT, properties = properties.toMap()))

    @Test
    fun `a duration is a number and a reference an id, its names in siblings when it reads them from there`() {
        schemaOf("timeout" to scalar(QueryValueType.INTEGER, seconds))
        schemaOf("timeout" to scalar(QueryValueType.DECIMAL, seconds))
        schemaOf("memberId" to scalar(QueryValueType.STRING, member))
        schemaOf("memberNo" to scalar(QueryValueType.INTEGER, member))
        schemaOf("productIds" to arrayFixture(scalar(QueryValueType.STRING, member)))
        schemaOf(
            "source" to objectFixture(
                "contextName" to scalarFixture(),
                "aggregateName" to scalarFixture(),
                "aggregateId" to scalar(QueryValueType.STRING, byFields),
            ),
        )
    }

    @Test
    fun `a wrong duration or reference, or an unknown semantic type, is a schema conflict`() {
        listOf(
            arrayOf("timeout" to scalar(QueryValueType.STRING, seconds)),
            arrayOf("timeout" to scalar(QueryValueType.BOOLEAN, seconds)),
            arrayOf("memberId" to scalar(QueryValueType.DECIMAL, member)),
            arrayOf("memberId" to scalar(QueryValueType.BOOLEAN, member)),
            // The names a reference reads must be single-valued string siblings.
            arrayOf("aggregateId" to scalar(QueryValueType.STRING, byFields), "aggregateName" to scalarFixture()),
            arrayOf(
                "aggregateId" to scalar(QueryValueType.STRING, byFields),
                "contextName" to scalarFixture(),
                "aggregateName" to arrayFixture(scalarFixture()),
            ),
            arrayOf("value" to scalar(QueryValueType.STRING, QuerySemanticType.Unknown)),
        ).forEach { properties ->
            assertThrows<QuerySchemaConflictException> { schemaOf(*properties) }
        }
    }

    @Test
    fun `annotations declare a duration or a reference, and a field keeps one semantic type`() {
        val declaration = objectFact(
            "timeout" to intFact(QueryDuration(TimeUnit.SECONDS)),
            "memberId" to stringFact(QueryReference("member")),
            "orderId" to stringFact(QueryReference("order", contextName = "sales")),
            "memberIds" to QueryTypeFact(
                QueryValueKind.ARRAY,
                setOf(QueryValueType.STRING),
                items = QueryTypeFact(QueryValueKind.SCALAR, setOf(QueryValueType.STRING)),
                member = QueryMemberFact("memberIds", List::class.java, listOf(QueryReference("member")), String::class.java),
            ),
        ).toDeclaration(QueryField("state"), contextName = "example")
        val properties = declaration.properties.valueOr(emptyMap())
        properties.getValue("timeout").semanticType.assert().isEqualTo(DeclarationValue.Set(seconds))
        properties.getValue("memberId").semanticType.assert().isEqualTo(DeclarationValue.Set(member))
        properties.getValue("orderId").semanticType.assert()
            .isEqualTo(DeclarationValue.Set(Reference(contextName = "sales", aggregateName = "order")))
        properties.getValue("memberIds").items.valueOr(null)!!.semanticType.assert()
            .isEqualTo(DeclarationValue.Set(member))

        listOf(
            intFact(QueryDuration(TimeUnit.SECONDS), QueryTemporal()),
            intFact(QueryDuration(TimeUnit.SECONDS), QueryReference("member")),
            intFact(QueryDuration(TimeUnit.SECONDS), QueryDuration(TimeUnit.MINUTES)),
            stringFact(QueryReference("member"), QueryReference("order")),
            stringFact(QueryReference("a member")),
            QueryTypeFact(
                QueryValueKind.OBJECT,
                setOf(QueryValueType.OBJECT),
                member = QueryMemberFact("value", Any::class.java, listOf(QueryDuration(TimeUnit.SECONDS))),
            ),
        ).forEach { fact ->
            assertThrows<QuerySchemaConflictException> {
                objectFact("value" to fact).toDeclaration(QueryField("state"), contextName = "example")
            }
        }
        // A reference into its own context needs the model's context.
        assertThrows<QuerySchemaConflictException> {
            objectFact("value" to stringFact(QueryReference("member"))).toDeclaration(QueryField("state"))
        }
    }

    @Test
    fun `an AggregateId member refers to the aggregate its own names name`() {
        val aggregateId = QueryTypeFact(
            QueryValueKind.OBJECT,
            setOf(QueryValueType.OBJECT),
            nullable = false,
            properties = listOf("contextName", "aggregateName", "tenantId", "aggregateId")
                .associateWith { QueryTypeFact(QueryValueKind.SCALAR, setOf(QueryValueType.STRING), nullable = false) },
            member = QueryMemberFact("source", AggregateId::class.java, emptyList()),
        )
        val source = objectFact("source" to aggregateId).toDeclaration(QueryField("state"))
            .properties.valueOr(emptyMap()).getValue("source").properties.valueOr(emptyMap())
        source.getValue("aggregateId").semanticType.assert().isEqualTo(DeclarationValue.Set(byFields))
        source.getValue("tenantId").semanticType.assert().isEqualTo(DeclarationValue.Set(null))
    }

    @Test
    fun `the descriptor publishes the semantics, and the model's times by role`() {
        val snapshot = boundSchemaFixture(
            objectFixture(
                "aggregateId" to scalarFixture(),
                "eventTime" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch()),
                "firstEventTime" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch()),
                "snapshotTime" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch()),
                "state" to objectFixture(
                    "timeout" to scalar(QueryValueType.INTEGER, seconds),
                    "memberId" to scalar(QueryValueType.STRING, member),
                ),
            ),
        ).describe(QueryBudget.HTTP_DEFAULT, 100).fields.associateBy { it.path }
        snapshot.getValue("state.timeout").semantic.assert().isEqualTo(seconds)
        snapshot.getValue("state.memberId").semantic.assert().isEqualTo(member)
        snapshot.getValue("eventTime").role.assert().isEqualTo(FieldDescriptor.EVENT_TIME)
        snapshot.getValue("firstEventTime").role.assert().isEqualTo(FieldDescriptor.FIRST_EVENT_TIME)
        snapshot.getValue("snapshotTime").role.assert().isNull()
        snapshot.getValue("aggregateId").role.assert().isEqualTo("AGGREGATE_ID")

        val events = boundSchemaFixture(
            objectFixture(
                "id" to scalarFixture(),
                "aggregateId" to scalarFixture(),
                "createTime" to scalarFixture(QueryValueType.INTEGER, Temporal.Epoch()),
            ),
            model = QueryModel.EVENT_STREAM,
        ).describe(QueryBudget.HTTP_DEFAULT, 100).fields.associateBy { it.path }
        events.getValue("createTime").role.assert().isEqualTo(FieldDescriptor.EVENT_TIME)
        events.values.map { it.role }.assert().doesNotContain(FieldDescriptor.FIRST_EVENT_TIME)
    }

    private fun objectFact(vararg properties: Pair<String, QueryTypeFact>) = QueryTypeFact(
        QueryValueKind.OBJECT,
        setOf(QueryValueType.OBJECT),
        nullable = false,
        properties = properties.toMap(),
    )

    private fun intFact(vararg annotations: Annotation) = QueryTypeFact(
        QueryValueKind.SCALAR,
        setOf(QueryValueType.INTEGER),
        nullable = false,
        member = QueryMemberFact("member", Int::class.java, annotations.toList()),
    )

    private fun stringFact(vararg annotations: Annotation) = QueryTypeFact(
        QueryValueKind.SCALAR,
        setOf(QueryValueType.STRING),
        nullable = false,
        member = QueryMemberFact("member", String::class.java, annotations.toList()),
    )
}
