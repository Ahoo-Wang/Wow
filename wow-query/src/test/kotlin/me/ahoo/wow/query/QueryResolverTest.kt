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

package me.ahoo.wow.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.AggregationQuery
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.IsNullFilter
import me.ahoo.wow.api.query.ListQuery
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.api.query.QueryErrorCodes
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.Sort
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.spec.SystemField
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.EventStreamQueryModelProfile
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryModelProfile
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryViolation
import me.ahoo.wow.query.schema.SnapshotQueryModelProfile
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.schema.systemField
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import tools.jackson.databind.node.JsonNodeFactory

class QueryResolverTest {
    private val confidential = scalarFixture(mask = MaskRule(SensitivityLevel.CONFIDENTIAL))
    private val schema = boundSchemaFixture(
        objectFixture(
            "aggregateId" to scalarFixture(),
            "deleted" to scalarFixture(QueryValueType.BOOLEAN),
            "state" to objectFixture(
                "name" to scalarFixture(),
                "buyer" to QueryValueSchema(
                    kind = scalarFixture().kind,
                    valueTypes = scalarFixture().valueTypes,
                    aliases = setOf(QueryField("state.customer")),
                ),
                "secret" to confidential,
                "items" to arrayFixture(objectFixture("qty" to scalarFixture(QueryValueType.INTEGER))),
            ),
        ),
    )
    private val text = JsonNodeFactory.instance.stringNode("x")

    private fun rejection(block: () -> Unit): QueryViolation =
        checkNotNull(assertThrows<QuerySchemaValidationException> { block() }.violation)

    private fun sortBy(vararg fields: String): IListQuery =
        ListQuery(MatchAllFilter, sort = fields.map { Sort(QueryField(it), Sort.Direction.ASC) })

    @Test
    fun `every sort is limited to the storage-wide field count`() {
        val many = (0..AggregationQuery.MAX_SORT_FIELDS).map { "state.name$it" }
        val tooMany = ListQuery(MatchAllFilter, sort = many.map { Sort(QueryField(it), Sort.Direction.ASC) })
        rejection { QueryAdmission.Trusted.list(tooMany, schema) }.apply {
            assert().isEqualTo(QueryViolation.SortTooMany(AggregationQuery.MAX_SORT_FIELDS))
            code.assert().isEqualTo(QueryErrorCodes.INVALID_REQUEST)
        }
    }

    @Test
    fun `two sort fields bound to one physical field are rejected for every query type`() {
        rejection { QueryAdmission.Trusted.list(sortBy("state.name", "state.name"), schema) }
            .assert().isEqualTo(QueryViolation.DuplicateSortField(QueryField("state.name")))
        // An alias names its canonical field, so it cannot sort by the same field twice either.
        rejection { QueryAdmission.Trusted.list(sortBy("state.buyer", "state.customer"), schema) }
            .assert().isEqualTo(QueryViolation.DuplicateSortField(QueryField("state.buyer")))
        QueryAdmission.Trusted.list(sortBy("state.name", "state.buyer"), schema).query.sort.map { it.field.path }
            .assert().containsExactly("state.name", "state.buyer")
    }

    @Test
    fun `violations name the absolute logical field, in element scopes too`() {
        val inside = ElementMatchFilter(QueryField("state.items"), EqualFilter(QueryField("qty"), text))
        rejection { QueryAdmission.Trusted.count(inside, schema) }
            .assert().isEqualTo(QueryViolation.ValueMismatch(QueryField("state.items.qty")))
    }

    @Test
    fun `lowered predicates keep the field they were resolved for`() {
        val admitted = QueryAdmission.Trusted.count(EqualFilter(QueryField("state.customer"), NULL), schema)
        val lowered = admitted.query as IsNullFilter
        lowered.field.assert().isEqualTo(QueryField("state.buyer"))
        admitted.field(lowered.field).logicalField.assert().isEqualTo(QueryField("state.buyer"))
    }

    @Test
    fun `a caller cannot compare a protected field, a policy restriction can`() {
        val secret = EqualFilter(QueryField("state.secret"), text)
        rejection { QueryAdmission.Trusted.count(secret, schema) }
            .assert().isEqualTo(QueryViolation.ProtectedComparison(QueryField("state.secret")))

        val policy = QueryPolicy { _, _ -> Mono.just(EqualFilter(QueryField("state.secret"), text)) }
        val admission = QueryAdmission(MaterializedNamedAggregate("resolver", "order"), policies = listOf(policy))
        val admitted = admission.admit(
            QueryOperation.COUNT,
            EqualFilter(QueryField("state.name"), text) as FilterExpression,
            Mono.just(schema)
        )
            .block()!!
        admitted.query.toString().assert().contains("state.secret")

        // Trust covers the protection gates only: a policy restriction must still fit the model.
        val unknown = QueryPolicy { _, _ -> Mono.just(EqualFilter(QueryField("state.unknown"), text)) }
        assertThrows<QuerySchemaValidationException> {
            QueryAdmission(MaterializedNamedAggregate("resolver", "order"), policies = listOf(unknown))
                .admit(
                    QueryOperation.COUNT,
                    EqualFilter(QueryField("state.name"), text) as FilterExpression,
                    Mono.just(schema)
                )
                .block()
        }.violation.assert().isEqualTo(QueryViolation.UnknownField(QueryField("state.unknown")))
    }

    @Test
    fun `one profile maps every system field`() {
        SnapshotQueryModelProfile.systemField(SystemField.IDENTITY).path.assert().isEqualTo(MessageRecords.AGGREGATE_ID)
        EventStreamQueryModelProfile.systemField(SystemField.IDENTITY).path.assert().isEqualTo(MessageRecords.ID)
        SnapshotQueryModelProfile.systemField(SystemField.TENANT_ID).path.assert().isEqualTo(MessageRecords.TENANT_ID)
        SnapshotQueryModelProfile.requiredScope.assert().isEqualTo(
            QueryModelProfile.metadataField(SystemField.TENANT_ID)
        )
        EventStreamQueryModelProfile.variantElement.path.assert().isEqualTo(MessageRecords.BODY)
        SnapshotQueryModelProfile.variantElement.assert().isNull()
        val custom = boundSchemaFixture(objectFixture("tenantId" to scalarFixture()), model = QueryModel("custom"))
        custom.systemField(SystemField.OWNER_ID).path.assert().isEqualTo(MessageRecords.OWNER_ID)
        assertThrows<QuerySchemaValidationException> { custom.systemField(SystemField.IDENTITY) }
    }

    private companion object {
        val NULL = JsonNodeFactory.instance.nullNode()
    }
}
