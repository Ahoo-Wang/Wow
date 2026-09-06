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

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.ISingleQuery
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.Mask
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
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaValidationException
import me.ahoo.wow.query.schema.QuerySchemaValidationMode
import me.ahoo.wow.query.snapshot.DefaultSnapshotQueryGateway
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryBackend
import me.ahoo.wow.query.snapshot.SnapshotQueryBackend
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Mono
import tools.jackson.databind.node.ObjectNode
import java.util.Optional

class RegisteredDeserializerMaskContractTest {
    @TestFactory
    fun `property declared subtypes preserve gateway typed masking`() = listOf(
        PropertyConstructorContact("customer-secret"),
        PropertySetterContact().apply { secret = "customer-secret" },
    ).map { contact ->
        DynamicTest.dynamicTest(contact.javaClass.simpleName) {
            val value = PropertySubtypeState(contact)
            val wire = JsonSerializer.writeValueAsString(value)
            val restored = JsonSerializer.readValue(wire, PropertySubtypeState::class.java)
            restored.value.javaClass.assert().isEqualTo(contact.javaClass)
            restored.value.secret.assert().isEqualTo("customer-secret")
            val result = materialize(value.javaClass, wire)
            result.maskedFields.assert().containsExactly(QueryField("state.value.secret"))
            val masked = (result.snapshot.state as PropertySubtypeState).value
            masked.javaClass.assert().isEqualTo(contact.javaClass)
            masked.secret.assert().isEqualTo("***************")
            JsonSerializer.readTree(JsonSerializer.writeValueAsString(result.snapshot.state))
                .at("/value/secret").stringValue().assert().isEqualTo("***************")
        }
    }

    @Test
    fun `property subtype declarations still reject read only concrete route`() {
        val value = UnsafePropertySubtypeState(PropertyReadOnlyContact())
        val wire = JsonSerializer.writeValueAsString(value)
        JsonSerializer.readValue(wire, UnsafePropertySubtypeState::class.java).value.secret.assert().isEqualTo("raw")
        assertThrows<QuerySchemaConflictException> { gateway(value.javaClass, wire) }
    }

    @Test
    fun `property subtype names without type info do not establish writable route`() {
        val value = UntypedPropertySubtypeState(PropertyConstructorContact("raw"))
        val wire = JsonSerializer.writeValueAsString(value)
        assertThrows<tools.jackson.databind.exc.InvalidDefinitionException> {
            JsonSerializer.readValue(wire, UntypedPropertySubtypeState::class.java)
        }
        assertThrows<QuerySchemaConflictException> { gateway(value.javaClass, wire) }
    }

    interface PropertyContact {
        @get:Mask val secret: String
    }

    data class PropertyConstructorContact(override val secret: String) : PropertyContact
    class PropertySetterContact : PropertyContact {
        override var secret: String = ""
    }
    class PropertyReadOnlyContact : PropertyContact {
        override val secret: String get() = "raw"
    }
    data class PropertySubtypeState(
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @get:JsonSubTypes(
            JsonSubTypes.Type(PropertyConstructorContact::class, name = "constructor"),
            JsonSubTypes.Type(PropertySetterContact::class, name = "setter"),
        )
        val value: PropertyContact,
    )
    data class UnsafePropertySubtypeState(
        @get:JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
        @get:JsonSubTypes(
            JsonSubTypes.Type(PropertyConstructorContact::class, name = "constructor"),
            JsonSubTypes.Type(PropertyReadOnlyContact::class, name = "readonly"),
        )
        val value: PropertyContact,
    )
    data class UntypedPropertySubtypeState(
        @get:JsonSubTypes(JsonSubTypes.Type(PropertyConstructorContact::class, name = "constructor"))
        val value: PropertyContact,
    )

    @TestFactory
    fun `raw masked strings must fail schema admission before query masking`() = listOf(
        RawMaskedString("""{"value":"customer-secret"}"""),
        GetterRawMaskedString("""{"value":"customer-secret"}"""),
    ).map { value ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            val wire = JsonSerializer.writeValueAsString(value)
            JsonSerializer.readTree(wire).at("/secret/value").stringValue().assert().isEqualTo("customer-secret")
            JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(
                value
            ).path("secret").isString.assert().isFalse()
            assertThrows<QuerySchemaConflictException> {
                val (gateway, schema) = gateway(value.javaClass, wire)
                schema.toMetadata().fields.filter { it.masked }.map { it.field }.assert()
                    .containsExactly(QueryField("state.secret"))
                assertThrows<QuerySchemaValidationException> { gateway.dynamicSingle(singleQuery {}).block() }
            }
        }
    }

    @TestFactory
    fun `inactive raw annotations preserve real masked string roundtrip`(): List<DynamicTest> {
        val raw = """{"value":"customer-secret"}"""
        return listOf(
            NativeMaskedElement(raw),
            DisabledRawMaskedString(raw),
            GetterOverridesRawString().apply { secret = raw },
            SetterRawString().apply { secret = raw },
            IgnoredRawString(raw),
        ).map { value ->
            DynamicTest.dynamicTest(value.javaClass.simpleName) {
                val wire = JsonSerializer.writeValueAsString(value)
                JsonSerializer.readTree(wire).path("secret").stringValue().assert().isEqualTo(raw)
                val (gateway, schema) = gateway(value.javaClass, wire)
                schema.toMetadata().fields.filter { it.masked }.map { it.field }.assert()
                    .containsExactly(QueryField("state.secret"))
                val masked = gateway.dynamicSingle(singleQuery {}).block()!!.path("state")
                masked.path("secret").stringValue().assert().isEqualTo("*".repeat(raw.length))
                val restored = JsonSerializer.treeToValue(masked, value.javaClass)
                JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(
                    restored
                ).path("secret").stringValue().assert()
                    .isEqualTo("*".repeat(raw.length))
                if (value is IgnoredRawString) masked.has("ignored").assert().isFalse()
            }
        }
    }

    @TestFactory
    fun `native optional and declared unwrapping preserve masked roundtrip`() = listOf(
        Triple(
            NativeOptionalState(Optional.of(NativeMaskedElement("native-secret"))),
            "/value/secret",
            "state.value.secret"
        ),
        Triple(NativePlainUnwrappedState(), "/secret", "state.secret"),
        Triple(NativeUnwrappedState(), "/pre_secret_s", "state.pre_secret_s"),
        Triple(NativeNestedUnwrappedState(), "/outer_pre_secret_s_out", "state.outer_pre_secret_s_out"),
    ).map { (value, path, maskPath) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            val wire = JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(value)
            if (value is NativeNestedUnwrappedState) {
                wire.toString().assert().isEqualTo("""{"outer_pre_secret_s_out":"native-secret"}""")
            }
            wire.at(path).stringValue().assert().isEqualTo("native-secret")
            val (gateway, schema) = gateway(value.javaClass, wire.toString())
            schema.toMetadata().fields.filter { it.masked }.map { it.field }.assert().containsExactly(
                QueryField(maskPath)
            )
            val state = gateway.dynamicSingle(singleQuery {}).block()!!.path("state")
            state.at(path).stringValue().assert().isEqualTo("*************")
            val restored = JsonSerializer.treeToValue(state, value.javaClass)
            JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(restored).at(path).stringValue().assert()
                .isEqualTo("*************")
        }
    }

    @Test
    fun `native nested layout that differs from declared prefixes remains rejected`() {
        val wire = JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(MismatchedNestedUnwrappedState())
        wire.at("/pre_secret_s").stringValue().assert().isEqualTo("native-secret")
        assertThrows<QuerySchemaConflictException> {
            gateway(
                MismatchedNestedUnwrappedState::class.java,
                wire.toString()
            )
        }
    }

    @Test
    fun `hidden iterator content cannot bypass mask declaration checks`() {
        val value = HiddenIteratorState(listOf(NativeMaskedElement("iterator-secret")).iterator())
        val wire = JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(value)
        wire.at("/values/0/secret").stringValue().assert().isEqualTo("iterator-secret")
        assertThrows<QuerySchemaConflictException> {
            val (gateway, schema) = gateway(value.javaClass, wire.toString())
            schema.toMetadata().fields.none { it.masked }.assert().isTrue()
            gateway.dynamicSingle(singleQuery {}).block()!!.at("/state/values/0/secret").stringValue().assert()
                .isEqualTo("iterator-secret")
        }
    }

    @Test
    fun `visible iterator requires a generator represented mask path`() {
        val schema = SchemaGeneratorBuilder().build().generateSchema(VisibleIteratorState::class.java)
        schema.toString().contains("\"secret\"").assert().isFalse()
        assertThrows<QuerySchemaConflictException> { gateway(VisibleIteratorState::class.java, "{\"values\":[]}") }
        gateway(PlainIteratorState::class.java, """{"values":["public"]}""").first
            .dynamicSingle(singleQuery {}).block()!!.at("/state/values/0").stringValue().assert().isEqualTo("public")
    }

    @TestFactory
    fun `registered serializers must not move masked source values beyond schema paths`() = listOf(
        Triple(
            BoundNativeOptionalState(Optional.of(OptionalBoundElement("spi-backend-secret"))),
            "/value/leaked_secret",
            "state.value.secret",
        ),
        Triple(DynamicUnwrappedMismatchState(), "/rogue_secret", "state.pre_secret_s"),
        Triple(
            BoundTypeListState(listOf(BoundTypeMaskedElement("spi-backend-secret"))),
            "/values/0/element/secret",
            "state.values.secret",
        ),
        Triple(
            BoundTypeArrayState(arrayOf(BoundTypeMaskedElement("spi-backend-secret"))),
            "/values/0/element/secret",
            "state.values.secret",
        ),
        Triple(
            BoundSerializerState(NativeMaskedElement("spi-backend-secret")),
            "/value/leaked_secret",
            "state.value.secret"
        ),
        Triple(DuplicateMaskedBean("spi-backend-secret"), "/leaked_secret", "state.secret"),
        Triple(OverriddenWriterBean("spi-backend-secret"), "/leaked_secret", "state.secret"),
        Triple(RenamedMaskedBean("spi-backend-secret"), "/leaked_secret", "state.secret"),
        Triple(OverriddenMaskedBean("spi-backend-secret"), "/leaked_secret", "state.secret"),
        Triple(
            ResolvedMaskedListState(listOf(SerializerMaskedElement("spi-backend-secret"))),
            "/values/0/leaked_secret",
            "state.values.secret"
        ),
        Triple(
            FlattenedMaskedState(FlattenedMaskedList().apply { add(SerializerMaskedElement("spi-backend-secret")) }),
            "/values/0/leaked_secret",
            "state.values.secret"
        ),
    ).map { (value, wirePath, maskPath) ->
        DynamicTest.dynamicTest(value.javaClass.simpleName) {
            val wire = JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(value)
            wire.at(wirePath).stringValue().assert().isEqualTo("spi-backend-secret")
            assertThrows<QuerySchemaConflictException> {
                val (gateway, schema) = gateway(value.javaClass, wire.toString())
                schema.toMetadata().fields.filter { it.masked }.map { it.field }.assert().containsExactly(
                    QueryField(maskPath)
                )
                gateway.dynamicSingle(singleQuery {}).block()!!.at("/state$wirePath").stringValue().assert()
                    .isEqualTo("spi-backend-secret")
            }
        }
    }

    @Test
    fun `bound content type wrappers do not originate in element annotations`() {
        JsonSerializer._serializationContext().findTypeSerializer(
            JsonSerializer.typeFactory.constructType(BoundTypeMaskedElement::class.java),
        ).assert().isNull()
    }

    @Test
    fun `bound property type ids preserve the native element mask path`() {
        val value = PropertyTypeListState(listOf(PropertyTypeMaskedElement("native-secret")))
        val wire = JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(value)
        wire.at("/values/0/kind").stringValue().assert().isEqualTo("element")
        wire.at("/values/0/secret").stringValue().assert().isEqualTo("native-secret")
        val (gateway, schema) = gateway(value.javaClass, wire.toString())
        schema.toMetadata().fields.filter { it.masked }.map { it.field }.assert()
            .containsExactly(QueryField("state.values.secret"))
        gateway.dynamicSingle(singleQuery {}).block()!!.at("/state/values/0/secret").stringValue().assert()
            .isEqualTo("*************")
    }

    @Test
    fun `registered property reordering retains real schema mask application`() {
        val value = ReorderedMaskedBean("safe-layout-secret")
        val wire = JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(value)
        val (gateway, schema) = gateway(value.javaClass, wire.toString())
        schema.toMetadata().fields.filter { it.masked }.map { it.field }.assert().containsExactly(
            QueryField("state.secret")
        )
        val result = gateway.dynamicSingle(singleQuery {}).block()!!
        result.at("/state/secret").stringValue().assert().isEqualTo("******************")
        result.at("/state/label").stringValue().assert().isEqualTo("visible")
    }

    @Test
    fun `native renamed fields and container layouts retain masking`() {
        val value = NativeMaskedLayout(
            "native-secret",
            listOf(NativeMaskedElement("list-secret")),
            arrayOf(NativeMaskedElement("array-secret")),
            mapOf("label" to "visible")
        )
        val wire = JsonSerializer.valueToTree<tools.jackson.databind.JsonNode>(value)
        val (gateway, schema) = gateway(value.javaClass, wire.toString())
        schema.toMetadata().fields.filter { it.masked }.map { it.field }.assert().containsExactlyInAnyOrder(
            QueryField("state.wire_secret"),
            QueryField("state.values.secret"),
            QueryField("state.elements.secret")
        )
        val result = gateway.dynamicSingle(singleQuery {}).block()!!
        result.at("/state/wire_secret").stringValue().assert().isEqualTo("*************")
        result.at("/state/values/0/secret").stringValue().assert().isEqualTo("***********")
        result.at("/state/elements/0/secret").stringValue().assert().isEqualTo("************")
        result.at("/state/labels/label").stringValue().assert().isEqualTo("visible")
    }

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
        val (gateway, schema) = gateway(stateType, stateJson)
        return MaterializationResult(
            gateway.single(singleQuery {}).block()!!,
            schema.toMetadata().fields.filter { it.masked }.map { it.field },
        )
    }

    private fun gateway(stateType: Class<*>, stateJson: String): Pair<DefaultSnapshotQueryGateway<Any>, QueryModelSchema> {
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
        return gateway to schema
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
