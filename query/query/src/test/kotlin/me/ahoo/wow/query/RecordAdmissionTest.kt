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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.AndFilter
import me.ahoo.wow.api.query.BetweenFilter
import me.ahoo.wow.api.query.ContainsFilter
import me.ahoo.wow.api.query.DeletionFilter
import me.ahoo.wow.api.query.DeletionState
import me.ahoo.wow.api.query.ElementMatchFilter
import me.ahoo.wow.api.query.EqualFilter
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.GreaterThanFilter
import me.ahoo.wow.api.query.IdFilter
import me.ahoo.wow.api.query.InFilter
import me.ahoo.wow.api.query.OwnerIdFilter
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.SearchFilter
import me.ahoo.wow.api.query.SingleQuery
import me.ahoo.wow.api.query.TenantIdFilter
import me.ahoo.wow.query.admission.QueryAdmission
import me.ahoo.wow.query.error.QueryRequestException
import me.ahoo.wow.query.error.QueryScopeRequiredException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.filter.QueryType
import me.ahoo.wow.query.policy.QueryEntryPolicy
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryValueSchema
import me.ahoo.wow.query.schema.QueryValueType
import me.ahoo.wow.query.schema.arrayFixture
import me.ahoo.wow.query.schema.boundSchemaFixture
import me.ahoo.wow.query.schema.objectFixture
import me.ahoo.wow.query.schema.scalarFixture
import me.ahoo.wow.query.schema.snapshotFixture
import me.ahoo.wow.spi.json.WowJson
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.BigInteger
import java.util.UUID

class RecordAdmissionTest {
    private val aggregateType = AggregateType("record", "admission")
    private val uuid = UUID.fromString("f0191fbe-b181-4531-84be-4e8609e32966")

    private fun record(tags: String = "{}", deleted: Boolean = false): ObjectNode =
        WowJson.mapper.readTree(
            """{"aggregateId":"a1","tenantId":"t1","version":3,"ownerId":"o1","spaceId":"s1","deleted":$deleted,
           "tags":$tags,"state":{"items":[{"sku":"x"},{"sku":"y"}],"name":"n","count":2,"empty":"",
           "nothing":null,"none":[],"codes":["a","b"],"uuid":"$uuid"}}"""
        ) as ObjectNode

    /** The snapshot schema of [record]'s state: its fields as Wow's JSON writes them. */
    private val schema = boundSchemaFixture(
        snapshotFixture(
            objectFixture(
                "items" to arrayFixture(objectFixture("sku" to scalarFixture())),
                "name" to scalarFixture(),
                "count" to scalarFixture(QueryValueType.INTEGER),
                "empty" to scalarFixture(),
                "codes" to arrayFixture(scalarFixture()),
                "uuid" to scalarFixture(),
            ),
        ),
    )

    @Test
    fun `a policy should admit the record as the in-memory backend evaluates it, every operator included`() {
        fun policy(filter: FilterExpression) = QueryAdmission(
            aggregateType,
            policies = listOf(QueryPolicy { _, _ -> filter })
        )
        val count = QueryField("state.count")
        // A range, an element of an array, text and numbers by value: the in-memory backend's semantics.
        admits(policy(GreaterThanFilter(count, 1)), record(), schema).assert().isTrue()
        admits(policy(GreaterThanFilter(count, 2)), record(), schema).assert().isFalse()
        admits(policy(BetweenFilter(count, 1L, 3L)), record(), schema).assert().isTrue()
        admits(policy(GreaterThanFilter(QueryField("version"), 0)), record(), schema).assert().isTrue()
        val items = QueryField("state.items")
        admits(
            policy(ElementMatchFilter(items, EqualFilter(QueryField("sku"), "y"))),
            record(),
            schema
        ).assert().isTrue()
        admits(
            policy(ElementMatchFilter(items, EqualFilter(QueryField("sku"), "z"))),
            record(),
            schema
        ).assert().isFalse()
        admits(policy(ContainsFilter(QueryField("state.name"), "n")), record(), schema).assert().isTrue()
        admits(policy(EqualFilter(count, BigInteger.TWO)), record(), schema).assert().isTrue()
        admits(policy(InFilter(QueryField("state.uuid"), listOf(uuid))), record(), schema).assert().isTrue()
        // EQ of null is lowered to IS_NULL first.
        admits(policy(EqualFilter(QueryField("state.name"), null)), record(), schema).assert().isFalse()
    }

    @Test
    fun `one admission should read many records alike, and follow a schema it is handed anew`() {
        val admission = QueryAdmission(
            aggregateType,
            policies = listOf(QueryPolicy { _, _ -> GreaterThanFilter(QueryField("state.count"), 1) }),
        )
        // Two reads under one schema: the second evaluates as the first did.
        admits(admission, record(), schema).assert().isTrue()
        admits(admission, record(), schema).assert().isTrue()
        admits(admission, record().also { (it.get("state") as ObjectNode).put("count", 0) }, schema).assert().isFalse()
        // A schema that no longer knows the field, as after a redeclaration: the policy then fails closed.
        val narrowed = boundSchemaFixture(snapshotFixture(objectFixture("name" to scalarFixture())))
        admits(admission, record(), narrowed).assert().isFalse()
        admits(admission, record(), schema).assert().isTrue()
    }

    @Test
    fun `what cannot be evaluated here should fail closed`() {
        fun policy(filter: FilterExpression) = QueryAdmission(
            aggregateType,
            policies = listOf(QueryPolicy { _, _ -> filter })
        )
        // A full-text search is the storage's, a field the schema does not know and a value its type refuses are
        // refused: the record is hidden.
        admits(policy(SearchFilter("n", setOf(QueryField("state.name")))), record(), schema).assert().isFalse()
        admits(policy(EqualFilter(QueryField("state.unknown"), "x")), record(), schema).assert().isFalse()
        // An element's field outside its ELEMENT_MATCH, which a query refuses too.
        admits(policy(EqualFilter(QueryField("state.items.sku"), "y")), record(), schema).assert().isFalse()
        admits(
            policy(BetweenFilter(QueryField("state.count"), BigDecimal("1.5"), 2L)),
            record(),
            schema
        ).assert().isFalse()
    }

    private fun admits(
        admission: QueryAdmission,
        record: ObjectNode,
        schema: QueryModelSchema = this.schema,
        selection: FilterExpression = IdFilter("a1"),
        scope: QueryScope = QueryScope.NONE,
    ): Boolean {
        var admitted: ObjectNode? = null
        runTest {
            admitted = admission.admitRecord(selection, record, schema, QueryCaller(entry = QueryEntry.HTTP, scope = scope))
        }
        return admitted != null
    }

    @Test
    fun `a point read runs the caller scope, the policies and the default deletion scope`() {
        val contexts = mutableListOf<QueryContext<*>>()
        fun policy(filter: FilterExpression) = QueryPolicy { _, context ->
            contexts += context
            filter
        }
        val schema = boundSchemaFixture(snapshotFixture(objectFixture("name" to scalarFixture())))
        val open = QueryAdmission(aggregateType)
        admits(open, record()).assert().isTrue()
        admits(open, record(), scope = QueryScope(authenticated = TenantIdFilter("t1"))).assert().isTrue()
        admits(open, record(), scope = QueryScope(declared = OwnerIdFilter("other"))).assert().isFalse()
        admits(open, record(), selection = IdFilter("a2")).assert().isFalse()

        val restricted = QueryAdmission(aggregateType, policies = listOf(policy(OwnerIdFilter("o1"))))
        admits(restricted, record(), schema).assert().isTrue()
        admits(QueryAdmission(aggregateType, policies = listOf(policy(OwnerIdFilter("x")))), record(), schema)
            .assert().isFalse()
        contexts.map { it.queryType to it.entry }.distinct().assert().containsExactly(
            QueryType.SINGLE to QueryEntry.HTTP
        )
        (contexts.first().query as SingleQuery).filter.assert().isEqualTo(IdFilter("a1"))

        // The model default hides a deleted record unless the selection or a policy states a deletion scope.
        admits(open, record(deleted = true)).assert().isFalse()
        admits(
            open,
            record(deleted = true),
            selection = AndFilter(listOf(IdFilter("a1"), DeletionFilter(DeletionState.ALL)))
        )
            .assert().isTrue()
        admits(
            QueryAdmission(aggregateType, policies = listOf(policy(DeletionFilter(DeletionState.DELETED)))),
            record(deleted = true),
            schema,
        ).assert().isTrue()
    }

    @Test
    fun `a point read resolves aliases`() {
        val schema = boundSchemaFixture(
            snapshotFixture(
                objectFixture(
                    "name" to QueryValueSchema(
                        kind = scalarFixture().kind,
                        valueTypes = scalarFixture().valueTypes,
                        aliases = setOf(QueryField("state.title")),
                    ),
                ),
            ),
        )
        val admission = QueryAdmission(aggregateType)
        val scope = QueryScope(declared = EqualFilter(QueryField("state.title"), "n"))
        admits(admission, record(), schema, scope = scope).assert().isTrue()
    }

    @Test
    fun `a point read runs the entry gates of a query route`() {
        val required = QueryAdmission(aggregateType, entryPolicy = QueryEntryPolicy(requireAuthenticatedScope = true))
        assertThrows<QueryScopeRequiredException> { admits(required, record()) }
        admits(required, record(), scope = QueryScope(authenticated = TenantIdFilter("t1"))).assert().isTrue()
        val explicit = QueryAdmission(aggregateType, entryPolicy = QueryEntryPolicy(requireExplicitEntry = true))
        assertThrows<QueryRequestException> {
            runTest { explicit.admitRecord(IdFilter("a1"), record(), schema, QueryCaller.UNSPECIFIED) }
        }
    }
}
