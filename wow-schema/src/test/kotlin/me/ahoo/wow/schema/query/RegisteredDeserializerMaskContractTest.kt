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

package me.ahoo.wow.schema.query

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.QueryBackendBinding
import me.ahoo.wow.query.ResolvedQuery
import me.ahoo.wow.query.dsl.singleQuery
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.QueryFieldSchema
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QueryRewriteMode
import me.ahoo.wow.query.schema.QuerySchemaBackendAdapter
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode

class RegisteredDeserializerMaskContractTest {
    @Test
    fun `registered root handler receives only the masked state`() {
        val result = materialize(RegisteredMaskedValue::class.java, """{"secret":"root-customer-secret"}""")
        val state = result.snapshot.state as RegisteredMaskedValue

        result.maskedFields.assert().containsExactly(QueryField("state.secret"))
        state.received.assert().isEqualTo("********************")
        state.received.assert().isNotEqualTo("root-customer-secret")
        state.secret.assert().isEqualTo(state.received)
    }

    @Test
    fun `registered property handler receives only the masked property`() {
        val result = materialize(
            RegisteredPropertyState::class.java,
            """{"value":{"secret":"property-customer-secret"}}""",
        )
        val value = (result.snapshot.state as RegisteredPropertyState).value

        result.maskedFields.assert().containsExactly(QueryField("state.value.secret"))
        value.received.assert().isEqualTo("************************")
        value.received.assert().isNotEqualTo("property-customer-secret")
        value.secret.assert().isEqualTo(value.received)
    }

    @Test
    fun `registered list content handler receives only masked elements`() {
        val result = materialize(
            RegisteredListState::class.java,
            """{"values":[{"secret":"first-customer-secret"},{"secret":"second-customer-secret"}]}""",
        )
        val values = (result.snapshot.state as RegisteredListState).values

        result.maskedFields.assert().containsExactly(QueryField("state.values.secret"))
        values.map { it.received }.assert().containsExactly("*********************", "**********************")
        values.map { it.received }.assert().doesNotContain("first-customer-secret", "second-customer-secret")
        values.map { it.secret }.assert().isEqualTo(values.map { it.received })
    }

    private fun materialize(stateType: Class<*>, stateJson: String): MaterializationResult {
        val context = QuerySchemaContext(NAMED_AGGREGATE, QueryModel.SNAPSHOT)
        val provider = DefaultQueryModelSchemaProvider(
            context,
            listOf(JsonQuerySchemaSource(typeResolver = { stateType })),
            IdentityQuerySchemaAdapter,
        )
        val schema = provider.schema().block()!!
        val node = snapshotNode(stateJson)
        val backend = object : SnapshotQueryBackend by NoOpSnapshotQueryBackend(NAMED_AGGREGATE) {
            override fun single(query: ResolvedQuery<ISingleQuery>): Mono<ObjectNode> = Mono.just(node.deepCopy())
        }
        val gateway = DefaultSnapshotQueryGateway<Any>(
            namedAggregate = NAMED_AGGREGATE,
            binding = QueryBackendBinding(backend, provider),
            validationMode = QuerySchemaValidationMode.COMPATIBLE,
            targetType = JsonSerializer.typeFactory.constructParametricType(
                MaterializedSnapshot::class.java,
                stateType,
            ),
        )
        return MaterializationResult(
            gateway.single(singleQuery {}).block()!!,
            schema.toMetadata().fields.filter { it.masked }.map { it.field },
        )
    }

    private fun snapshotNode(stateJson: String): ObjectNode = JsonSerializer.readTree(
        """
        {
          "contextName":"test",
          "aggregateName":"registered-handler",
          "tenantId":"tenant",
          "ownerId":"_default_",
          "spaceId":"_default_",
          "aggregateId":"aggregate",
          "version":1,
          "eventId":"event",
          "firstOperator":"operator",
          "operator":"operator",
          "firstEventTime":1,
          "eventTime":1,
          "state":$stateJson,
          "snapshotTime":1,
          "tags":{},
          "deleted":false
        }
        """.trimIndent(),
    ) as ObjectNode

    private data class MaterializationResult(
        val snapshot: MaterializedSnapshot<Any>,
        val maskedFields: List<QueryField>,
    )

    private object IdentityQuerySchemaAdapter : QuerySchemaBackendAdapter {
        override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> = Mono.just(
            QueryModelSchema(
                QueryModel.SNAPSHOT,
                emptySet(),
                logicalSchema.fields.mapValues { (_, field) ->
                    QueryFieldSchema(
                        title = field.title,
                        description = field.description,
                        enumValues = field.enumValues,
                        valueTypes = field.valueTypes,
                        nullable = field.nullable,
                        required = field.required,
                        cardinality = field.cardinality,
                        semanticType = field.semanticType,
                        dynamicChildren = field.dynamicChildren,
                        bindings = emptyMap(),
                        rewriteMode = QueryRewriteMode.NONE,
                        maskRule = field.maskRule,
                    )
                },
            ),
        )
    }

    private companion object {
        val NAMED_AGGREGATE: NamedAggregate = MaterializedNamedAggregate("test", "registered-handler")
    }
}
