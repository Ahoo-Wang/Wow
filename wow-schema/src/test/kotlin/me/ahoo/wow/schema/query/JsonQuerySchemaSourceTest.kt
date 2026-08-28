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
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.schema.MockEmptyAggregate
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class JsonQuerySchemaSourceTest {
    private val context = QuerySchemaContext(
        MaterializedNamedAggregate("test-context", "test-aggregate"),
        QueryModel.SNAPSHOT,
    )

    @Test
    fun `should use JSON Schema priority`() {
        JsonQuerySchemaSource(typeResolver = { StructuralState::class.java }).priority.assert()
            .isEqualTo(QuerySchemaSourcePriority.JSON_SCHEMA)
    }

    @Test
    fun `should infer event payload fields for event stream model`() {
        val eventStreamContext = QuerySchemaContext(
            Cart::class.java.aggregateMetadata<Any, Any>().namedAggregate,
            QueryModel.EVENT_STREAM,
        )
        val declaration = JsonQuerySchemaSource().load(eventStreamContext).single().block()!!

        declaration.fields.keys.assert()
            .contains(LogicalField("body.body.added.productId"))
            .contains(LogicalField("body.body.added.quantity"))
            .contains(LogicalField("body.body.productIds"))
            .contains(LogicalField("body.body.changed.productId"))
            .contains(LogicalField("body.body.changed.quantity"))
        declaration.field("body.body.added.productId").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        declaration.field("body.body.productIds").cardinality.assert()
            .isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
        declaration.field("body.body.changed.quantity").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        declaration.field("body.body.added").required.assert()
            .isEqualTo(DeclarationValue.Set(false))
    }

    @Test
    fun `should infer aggregate state fields for snapshot model`() {
        val snapshotContext = QuerySchemaContext(
            Cart::class.java.aggregateMetadata<Any, Any>().namedAggregate,
            QueryModel.SNAPSHOT,
        )

        JsonQuerySchemaSource().load(snapshotContext).single().block()!!
            .fields.keys.assert().contains(LogicalField("state.items.productId"))
    }

    @Test
    fun `should cache the same type independently by query model`() {
        val source = JsonQuerySchemaSource(typeResolver = { Cart::class.java })

        source.load(context).single().block()!!
        val eventStream = source.load(context.copy(model = QueryModel.EVENT_STREAM)).single().block()!!

        eventStream.fields.keys.assert().contains(LogicalField("body.body.added.productId"))
    }

    @Test
    fun `should return an empty declaration when aggregate events are unknown`() {
        JsonQuerySchemaSource(typeResolver = { MockEmptyAggregate::class.java })
            .load(context.copy(model = QueryModel.EVENT_STREAM)).single().block()!!
            .fields.assert().isEmpty()
    }

    @Test
    fun `should ignore unsupported query models`() {
        val resolutions = AtomicInteger()
        val source = JsonQuerySchemaSource(
            typeResolver = {
                resolutions.incrementAndGet()
                StructuralState::class.java
            },
        )

        source.load(context.copy(model = QueryModel("OTHER"))).collectList().block().assert().isEmpty()
        resolutions.get().assert().isZero()
    }

    @Test
    fun `should reuse inferred declaration for the same state type across contexts`() {
        val source = JsonQuerySchemaSource(typeResolver = { StructuralState::class.java })
        val otherContext = context.copy(
            namedAggregate = MaterializedNamedAggregate("other-context", "other-aggregate"),
        )

        val first = source.load(context).single().block()!!
        val second = source.load(otherContext).single().block()!!

        second.assert().isSameAs(first)
    }

    @Test
    fun `should infer once for concurrent contexts sharing a state type`() {
        val inferenceCount = AtomicInteger()
        val source = JsonQuerySchemaSource(
            typeResolver = { StructuralState::class.java },
            declarationResolver = { _, _ ->
                val inference = inferenceCount.incrementAndGet()
                QuerySchemaDeclaration(
                    mapOf(
                        LogicalField("state") to QueryFieldDeclaration(
                            title = DeclarationValue.Set("inference-$inference"),
                        ),
                    ),
                )
            },
        )
        val contexts = (0 until 32).map { index ->
            context.copy(namedAggregate = MaterializedNamedAggregate("context-$index", "aggregate-$index"))
        }

        val declarations = Flux.merge(
            contexts.map { loadContext ->
                source.load(loadContext).single().subscribeOn(Schedulers.parallel())
            },
        ).collectList().block()!!

        inferenceCount.get().assert().isEqualTo(1)
        declarations.all { it === declarations.first() }.assert().isTrue()
    }

    @Test
    fun `should infer away from the subscription calling thread`() {
        val subscriptionThread = Thread.currentThread()
        val stateTypeResolutionThread = AtomicReference<Thread>()
        val declarationResolutionThread = AtomicReference<Thread>()
        val source = JsonQuerySchemaSource(
            typeResolver = {
                stateTypeResolutionThread.set(Thread.currentThread())
                StructuralState::class.java
            },
            declarationResolver = { _, _ ->
                declarationResolutionThread.set(Thread.currentThread())
                QuerySchemaDeclaration(emptyMap())
            },
        )

        source.load(context).single().block()

        stateTypeResolutionThread.get().assert().isNotSameAs(subscriptionThread)
        declarationResolutionThread.get().assert().isNotSameAs(subscriptionThread)
    }

    @Test
    fun `should cache different state types independently`() {
        val inferenceCounts = ConcurrentHashMap<Class<*>, AtomicInteger>()
        val source = JsonQuerySchemaSource(
            typeResolver = { loadContext ->
                if (loadContext.namedAggregate.aggregateName == "structural") {
                    StructuralState::class.java
                } else {
                    JacksonState::class.java
                }
            },
            declarationResolver = { _, stateType ->
                inferenceCounts.computeIfAbsent(stateType) { AtomicInteger() }.incrementAndGet()
                QuerySchemaDeclaration(emptyMap())
            },
        )
        val structuralContext = context.copy(
            namedAggregate = MaterializedNamedAggregate("test-context", "structural"),
        )
        val jacksonContext = context.copy(
            namedAggregate = MaterializedNamedAggregate("test-context", "jackson"),
        )

        val structural = source.load(structuralContext).single().block()!!
        source.load(structuralContext).single().block()
        val jackson = source.load(jacksonContext).single().block()!!
        source.load(jacksonContext).single().block()

        inferenceCounts.getValue(StructuralState::class.java).get().assert().isEqualTo(1)
        inferenceCounts.getValue(JacksonState::class.java).get().assert().isEqualTo(1)
        jackson.assert().isNotSameAs(structural)
    }

    @Test
    fun `should retry inference after a failed cache computation`() {
        val failure = IllegalStateException("inference failed")
        val inferenceCount = AtomicInteger()
        val recovered = QuerySchemaDeclaration(emptyMap())
        val source = JsonQuerySchemaSource(
            typeResolver = { StructuralState::class.java },
            declarationResolver = { _, _ ->
                if (inferenceCount.incrementAndGet() == 1) throw failure
                recovered
            },
        )

        assertThrows<QuerySchemaUnavailableException> {
            source.load(context).single().block()
        }.cause.assert().isSameAs(failure)
        source.load(context).single().block().assert().isSameAs(recovered)
        source.load(context).single().block().assert().isSameAs(recovered)
        inferenceCount.get().assert().isEqualTo(2)
    }

    @Test
    fun `should wrap resolver failures as unavailable`() {
        val failure = IllegalStateException("resolver failed")

        assertThrows<QuerySchemaUnavailableException> {
            JsonQuerySchemaSource(typeResolver = { throw failure }).load(context).single().block()
        }.cause.assert().isSameAs(failure)
    }

    @Test
    fun `should preserve query schema failures`() {
        val failure = QuerySchemaConflictException("schema conflict")

        assertThrows<QuerySchemaConflictException> {
            JsonQuerySchemaSource(typeResolver = { throw failure }).load(context).single().block()
        }.assert().isSameAs(failure)
    }

    @Test
    fun `should infer structural and descriptive declarations`() {
        val declaration = load(StructuralState::class.java)

        declaration.field("state").assert().isEqualTo(
            QueryFieldDeclaration(
                title = DeclarationValue.Set("State title"),
                description = DeclarationValue.Set("State description"),
                enumValues = DeclarationValue.Set(null),
                dynamicChildren = DeclarationValue.Set(false),
            ),
        )
        declaration.field("state.count").assert().isEqualTo(
            declaration(
                title = "Count title",
                description = "Count description",
                valueTypes = setOf(QueryValueType.INTEGER),
                required = true,
            ),
        )
        declaration.field("state.ratio").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.DECIMAL)))
        declaration.field("state.active").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.BOOLEAN)))
        declaration.field("state.optional").assert().isEqualTo(
            declaration(
                valueTypes = setOf(QueryValueType.STRING),
                nullable = true,
                required = false,
            ),
        )
        declaration.field("state.status").let { status ->
            status.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
            checkNotNull((status.enumValues as DeclarationValue.Set).value).map { it.stringValue() }.assert()
                .containsExactly("ACTIVE", "INACTIVE")
        }
        declaration.field("state.address").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
        declaration.field("state.address.city").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        declaration.field("state.items").let { items ->
            items.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            items.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
        }
        declaration.field("state.items.quantity").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        declaration.field("state.tags").let { tags ->
            tags.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
            tags.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
        }
    }

    @Test
    fun `should follow Jackson property shape and reject illegal logical segments`() {
        val declaration = load(JacksonState::class.java)

        declaration.fields.keys.assert()
            .contains(LogicalField("state.display_name"))
            .contains(LogicalField("state.detail_nested_value"))
            .contains(LogicalField("state.visible"))
            .doesNotContain(LogicalField("state.secret"))
        declaration.fields.keys.any { it.value in setOf("state.display.name", "state.display name", "state.0") }
            .assert().isFalse()
        declaration.fields.keys.any { it.value.startsWith("state.details") }.assert().isFalse()
    }

    @Test
    fun `should treat custom serializer wire shapes as opaque`() {
        val declaration = load(CustomSerializerState::class.java)

        declaration.field("state.typeValue").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(emptySet<QueryValueType>()))
        declaration.field("state.propertyValue").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(emptySet<QueryValueType>()))
        declaration.fields.keys.any { it.value.endsWith(".hidden") }.assert().isFalse()
    }

    @Test
    fun `should traverse ref and all schema composition branches`() {
        val declaration = load(CompositionState::class.java)

        declaration.fields.keys.assert()
            .contains(LogicalField("state.allOf.inherited"))
            .contains(LogicalField("state.allOf.own"))
            .contains(LogicalField("state.anyOf.left"))
            .contains(LogicalField("state.anyOf.right"))
            .contains(LogicalField("state.oneOf.first"))
            .contains(LogicalField("state.oneOf.second"))
            .contains(LogicalField("state.payment.kind"))
            .contains(LogicalField("state.payment.cardNumber"))
            .contains(LogicalField("state.payment.account"))
        listOf("state.allOf.inherited", "state.allOf.own").forEach { field ->
            declaration.field(field).required.assert().isEqualTo(DeclarationValue.Set(true))
        }
        listOf(
            "state.anyOf.left",
            "state.anyOf.right",
            "state.oneOf.first",
            "state.oneOf.second",
        ).forEach { field ->
            declaration.field(field).required.assert().isEqualTo(DeclarationValue.Set(false))
        }
    }

    @Test
    fun `should merge repeated composition fields independent of branch order`() {
        val declaration = load(RepeatedCompositionState::class.java)
        val expectedTypes = DeclarationValue.Set(setOf(QueryValueType.STRING, QueryValueType.INTEGER))
        val forward = declaration.field("state.forward.value")
        val reverse = declaration.field("state.reverse.value")

        forward.valueTypes.assert().isEqualTo(expectedTypes)
        reverse.valueTypes.assert().isEqualTo(expectedTypes)
        forward.required.assert().isEqualTo(DeclarationValue.Set(true))
        reverse.required.assert().isEqualTo(DeclarationValue.Set(true))
        reverse.assert().isEqualTo(forward)
    }

    @Test
    fun `should mark a shared alternative field optional when only some branches require it`() {
        load(PartiallyRequiredCompositionState::class.java)
            .field("state.value.shared")
            .required.assert().isEqualTo(DeclarationValue.Set(false))
    }

    @Test
    fun `should reject disjoint value types for the same allOf field`() {
        assertThrows<QuerySchemaConflictException> {
            load(ConflictingAllOfValueTypesState::class.java)
        }
    }

    @Test
    fun `should narrow number and integer allOf fields to integer independent of branch order`() {
        val declaration = load(NumericSubtypeAllOfValueTypesState::class.java)
        val expected = DeclarationValue.Set(setOf(QueryValueType.INTEGER))

        declaration.field("state.forward.value").valueTypes.assert().isEqualTo(expected)
        declaration.field("state.reverse.value").valueTypes.assert().isEqualTo(expected)
    }

    @Test
    fun `should retain known value types when allOf also has opaque schemas`() {
        val declaration = load(OpaqueAllOfValueTypesState::class.java)

        declaration.field("state.known.value").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        declaration.field("state.opaque.value").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(emptySet<QueryValueType>()))
    }

    @Test
    fun `should reject conflicting composition metadata`() {
        assertThrows<QuerySchemaConflictException> {
            load(ConflictingCompositionState::class.java)
        }
    }

    @Test
    fun `should reject conflicting container metadata independent of branch order`() {
        listOf(ForwardMetadataState::class.java, ReverseMetadataState::class.java).forEach { type ->
            assertThrows<QuerySchemaConflictException> { load(type) }
        }
    }

    @Test
    fun `should reject conflicting container enums independent of branch order`() {
        listOf(ForwardEnumState::class.java, ReverseEnumState::class.java).forEach { type ->
            assertThrows<QuerySchemaConflictException> { load(type) }
        }
    }

    @Test
    fun `should retain equal container metadata and semantic type`() {
        val declaration = load(EqualContainerMetadataState::class.java)

        declaration.field("state.metadata").let { metadata ->
            metadata.title.assert().isEqualTo(DeclarationValue.Set("Shared title"))
            metadata.description.assert().isEqualTo(DeclarationValue.Set("Shared description"))
        }
        declaration.field("state.temporal").semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
    }

    @Test
    fun `should not infer temporal semantics from only one alternative`() {
        val declaration = load(MixedTemporalAlternativeState::class.java)

        listOf("state.anyOf", "state.oneOf").forEach { field ->
            declaration.field(field).semanticType.assert().isEqualTo(DeclarationValue.Set(null))
        }
    }

    @Test
    fun `should retain recursive fields without repeating descendants`() {
        val declaration = load(RecursiveState::class.java)

        declaration.fields.keys.assert()
            .contains(LogicalField("state.child"))
            .contains(LogicalField("state.children"))
        declaration.field("state.child").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
        declaration.field("state.children").cardinality.assert()
            .isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
        declaration.fields.keys.any {
            it.value.startsWith("state.child.") || it.value.startsWith("state.children.")
        }.assert().isFalse()
    }

    @Test
    fun `should not truncate deep acyclic state paths`() {
        load(DeepLevelOne::class.java).fields.keys.assert()
            .contains(LogicalField("state.two.three.four.five.six.value"))
    }

    @Test
    fun `should mark object additional properties as dynamic`() {
        val declaration = load(DynamicState::class.java)

        declaration.field("state.attributes").let { attributes ->
            attributes.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            attributes.dynamicChildren.assert().isEqualTo(DeclarationValue.Set(true))
        }
        declaration.field("state.attributeGroups").let { attributeGroups ->
            attributeGroups.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            attributeGroups.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
            attributeGroups.dynamicChildren.assert().isEqualTo(DeclarationValue.Set(true))
        }
        declaration.field("state.closed").dynamicChildren.assert().isEqualTo(DeclarationValue.Set(false))
    }

    @Test
    fun `should detect object and explicit true additional properties`() {
        mapOf(
            """{"additionalProperties":{"type":"string"}}""" to true,
            """{"additionalProperties":true}""" to true,
            """{"additionalProperties":false}""" to false,
        ).forEach { (schema, expected) ->
            JsonSerializer.readTree(schema).hasAdditionalProperties().assert().isEqualTo(expected)
        }
    }

    @Test
    fun `should infer native date formats`() {
        val declaration = load(NativeTemporalState::class.java)

        listOf("state.date", "state.instant", "state.instants").forEach { field ->
            declaration.field(field).semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        }
        declaration.field("state.instants").cardinality.assert()
            .isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
    }

    @Test
    fun `integer temporal annotation should override structural inference`() {
        val declaration = load(AnnotatedTemporalState::class.java)

        declaration.field("state.created_at").let { createdAt ->
            createdAt.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
            createdAt.semanticType.assert().isEqualTo(
                DeclarationValue.Set(Temporal.Epoch(TimeUnit.SECONDS)),
            )
        }
        declaration.field("state.timestamps").let { timestamps ->
            timestamps.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
            timestamps.cardinality.assert().isEqualTo(DeclarationValue.Set(QueryCardinality.MANY))
            timestamps.semanticType.assert().isEqualTo(
                DeclarationValue.Set(Temporal.Epoch(TimeUnit.MILLISECONDS)),
            )
        }
    }

    @Test
    fun `should reject temporal annotation on non integer wire shape`() {
        assertThrownBy<QuerySchemaConflictException> {
            load(InvalidTemporalState::class.java)
        }
    }

    private fun load(type: Class<*>): QuerySchemaDeclaration =
        JsonQuerySchemaSource(typeResolver = { type }).load(context).single().block()!!

    private fun QuerySchemaDeclaration.field(name: String): QueryFieldDeclaration = fields.getValue(LogicalField(name))

    private fun declaration(
        title: String? = null,
        description: String? = null,
        valueTypes: Set<QueryValueType>,
        nullable: Boolean = false,
        required: Boolean = true,
        cardinality: QueryCardinality = QueryCardinality.SINGLE,
    ) = QueryFieldDeclaration(
        title = DeclarationValue.Set(title),
        description = DeclarationValue.Set(description),
        enumValues = DeclarationValue.Set(null),
        valueTypes = DeclarationValue.Set(valueTypes),
        nullable = DeclarationValue.Set(nullable),
        required = DeclarationValue.Set(required),
        cardinality = DeclarationValue.Set(cardinality),
        semanticType = DeclarationValue.Set(null),
        dynamicChildren = DeclarationValue.Set(false),
    )
}
