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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.example.api.cart.CartItemAdded
import me.ahoo.wow.example.api.cart.CartItemRemoved
import me.ahoo.wow.example.api.cart.CartQuantityChanged
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.DefaultQueryModelSchemaProvider
import me.ahoo.wow.query.schema.LogicalQuerySchema
import me.ahoo.wow.query.schema.MaskRule
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QueryModelSchema
import me.ahoo.wow.query.schema.QuerySchemaBackendAdapter
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import me.ahoo.wow.query.schema.QuerySchemaUnavailableException
import me.ahoo.wow.schema.MockEmptyAggregate
import me.ahoo.wow.schema.query.maskfixture.privateMaskStrategyStateType
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.test.StepVerifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter

@Suppress("LargeClass")
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
        checkNotNull((declaration.field("body.bodyType").enumValues as DeclarationValue.Set).value)
            .map { it.stringValue() }
            .assert()
            .containsExactly(
                CartItemAdded::class.java.name,
                CartItemRemoved::class.java.name,
                CartQuantityChanged::class.java.name,
            )
    }

    @Test
    fun `should merge event body type metadata through default provider`() {
        val eventStreamContext = QuerySchemaContext(
            Cart::class.java.aggregateMetadata<Any, Any>().namedAggregate,
            QueryModel.EVENT_STREAM,
        )
        val resolved = AtomicReference<LogicalQuerySchema>()
        val provider = DefaultQueryModelSchemaProvider(
            eventStreamContext,
            listOf(JsonQuerySchemaSource()),
            object : QuerySchemaBackendAdapter {
                override fun resolve(logicalSchema: LogicalQuerySchema): Mono<QueryModelSchema> {
                    resolved.set(logicalSchema)
                    return Mono.just(QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), emptyMap()))
                }
            },
        )

        provider.schema().block()!!

        checkNotNull(resolved.get().fields.getValue(LogicalField("body.bodyType")).enumValues)
            .map { it.stringValue() }
            .assert()
            .containsExactly(
                CartItemAdded::class.java.name,
                CartItemRemoved::class.java.name,
                CartQuantityChanged::class.java.name,
            )
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
    fun `should resolve generated descriptive metadata by source precedence`() {
        val (declaration, warnings) = captureMetadataWarnings {
            load(DescriptiveMetadataState::class.java)
        }

        declaration.field("state.status").let { status ->
            status.title.assert().isEqualTo(DeclarationValue.Set("Account status"))
            status.description.assert().isEqualTo(DeclarationValue.Set("Account status description"))
            checkNotNull((status.enumValues as DeclarationValue.Set).value)
                .map { it.stringValue() }
                .assert().containsExactly("OK", "DISABLED")
        }
        declaration.field("state.equalStatus").let { status ->
            status.title.assert().isEqualTo(DeclarationValue.Set("Shared status"))
            status.description.assert().isEqualTo(DeclarationValue.Set("Shared status description"))
        }
        declaration.field("state.referencedStatus").let { status ->
            status.title.assert().isEqualTo(DeclarationValue.Set("Referenced status"))
            status.description.assert().isEqualTo(DeclarationValue.Set("Referenced status description"))
        }
        warnings.assert().hasSize(2)
        warnings.first().assert()
            .contains("field=state.status")
            .contains("property=title")
            .contains("selected=Account status")
            .contains("ignored=[Bank account status enum]")
            .contains("inline-allOf > referenced-type")
        warnings.last().assert()
            .contains("field=state.status")
            .contains("property=description")
            .contains("selected=Account status description")
            .contains("ignored=[Bank account status enum description]")
    }

    @Test
    fun `should deterministically prefer nearest metadata through nested refs and allOf`() {
        val schema =
            """
            {"definitions":{
              "BaseStatus":{"type":"string","enum":["OK","DISABLED"],"title":"Deep referenced title"},
              "WrappedStatus":{"allOf":[{"${'$'}ref":"#/definitions/BaseStatus"},
                {"title":"Referenced composition title"}]}},
             "properties":{"status":{"title":"Direct member title","allOf":[
               {"${'$'}ref":"#/definitions/WrappedStatus"},
               {"allOf":[{"title":"Deeper field composition title"}]},{"title":"Nearest field title"}]}}}
            """.trimIndent()

        val inferences = (1..5).map {
            assertTitleResolution(
                schema = schema,
                field = "state.status",
                selected = "Direct member title",
                ignored = listOf(
                    "Nearest field title",
                    "Deeper field composition title",
                    "Referenced composition title",
                    "Deep referenced title",
                ),
                precedence = "member > inline-allOf > deeper-composition",
            )
        }

        inferences.forEach { (declaration) ->
            checkNotNull((declaration.field("state.status").enumValues as DeclarationValue.Set).value)
                .map { it.stringValue() }
                .assert().containsExactly("OK", "DISABLED")
        }
        inferences.map { (_, warning) -> warning }.distinct().assert().hasSize(1)
    }

    @Test
    fun `should preserve direct member precedence across outer compositions and refs`() {
        mapOf(
            "inline-allOf" to
                """{"properties":{"value":{"type":"string","title":"Alpha inline"}}}""",
            "referenced-type" to
                """{"${'$'}ref":"#/definitions/Inherited"}""",
        ).forEach { (ignoredSource, branch) ->
            val schema =
                """
                {"definitions":{"Inherited":{"properties":{"value":{"type":"string","title":"Alpha referenced"}}}},
                 "properties":{"value":{"type":"string","title":"Zulu member"}},"allOf":[$branch]}
                """.trimIndent()

            assertTitleResolution(
                schema = schema,
                field = "state.value",
                selected = "Zulu member",
                ignored = listOf(if (ignoredSource == "inline-allOf") "Alpha inline" else "Alpha referenced"),
                precedence = "member > $ignoredSource",
            )
        }
    }

    @Test
    fun `should prefer referenced type metadata over deeper compositions`() {
        listOf(
            """
            {"definitions":{"Status":{"type":"string","title":"Zulu referenced title",
             "allOf":[{"title":"Alpha deeper title"}]}},
             "properties":{"status":{"${'$'}ref":"#/definitions/Status"}}}
            """.trimIndent() to "state.status",
            """
            {"definitions":{"Value":{"type":"string","title":"Zulu referenced title"},
             "State":{"properties":{"value":{"${'$'}ref":"#/definitions/Value"}},
             "allOf":[{"properties":{"value":{"allOf":[{"title":"Alpha deeper title"}]}}}]}},
             "${'$'}ref":"#/definitions/State"}
            """.trimIndent() to "state.value",
        ).forEach { (schema, field) ->
            assertTitleResolution(
                schema = schema,
                field = field,
                selected = "Zulu referenced title",
                ignored = listOf("Alpha deeper title"),
                precedence = "referenced-type > deeper-composition",
            )
        }
    }

    @Test
    fun `should prefer the nearer declaration within deeper compositions`() {
        assertTitleResolution(
            schema = """{"properties":{"value":{"allOf":[{"allOf":[{"title":"Zulu nearer"}]},{"allOf":[{"allOf":[{"title":"Alpha farther"}]}]}]}}}""",
            field = "state.value",
            selected = "Zulu nearer",
            ignored = listOf("Alpha farther"),
            precedence = "deeper-composition",
        )
    }

    @Test
    fun `should preserve outer composition provenance for nested fields`() {
        assertTitleResolution(
            schema = """{"properties":{"container":{"type":"object","properties":{"name":{"type":"string","title":"Zulu member"}}}},"allOf":[{"properties":{"container":{"type":"object","properties":{"name":{"type":"string","title":"Alpha inline"}}}}}]}""",
            field = "state.container.name",
            selected = "Zulu member",
            ignored = listOf("Alpha inline"),
            precedence = "member > inline-allOf",
        )
    }

    @Test
    fun `should emit one canonical warning for all same precedence values independent of order`() {
        val cases = listOf(
            Triple(listOf("Charlie", "Bravo", "Alpha"), "Alpha", listOf("Bravo", "Charlie")),
            Triple(listOf("Alpha", "Bravo", "Charlie"), "Alpha", listOf("Bravo", "Charlie")),
            Triple(listOf("First", "Second", "Second"), "First", listOf("Second")),
        )
        val warnings = cases.map { (values, selected, ignored) ->
            val branches = values.joinToString(",") { value ->
                """{"properties":{"value":{"type":"string","title":"$value"}}}"""
            }
            assertTitleResolution(
                schema = """{"oneOf":[$branches]}""",
                field = "state.value",
                selected = selected,
                ignored = ignored,
                precedence = "stable-value-order",
            ).second
        }
        warnings.take(2).distinct().assert().hasSize(1)
    }

    @Test
    fun `should keep structural and security metadata conflicts fail closed`() {
        mapOf(
            "enumValues" to
                """{"properties":{"value":{"allOf":[{"type":"string","enum":["A"]},{"type":"string","enum":["B"]}]}}}""",
            "maskRule" to
                """{"properties":{"value":{"allOf":[{"type":"string","$MASK_RULE_ATTRIBUTE":"0"},{"type":"string","$MASK_RULE_ATTRIBUTE":"1"}]}}}""",
            "semanticType" to
                """{"properties":{"value":{"allOf":[{"type":"integer","$TEMPORAL_UNIT":"SECONDS"},{"type":"integer","$TEMPORAL_UNIT":"MILLISECONDS"}]}}}""",
        ).forEach { (property, schema) ->
            assertThrows<QuerySchemaConflictException> {
                loadSchema(schema)
            }.message.assert().contains("state.value.$property")
        }
    }

    @Test
    fun `should reject masked illegal logical property names`() {
        val error = assertThrows<QuerySchemaConflictException> {
            load(MaskedInvalidLogicalFieldState::class.java)
        }

        error.message.assert().contains("state[\"phone.number\"]")
    }

    @Test
    fun `should reject masked descendants behind schema compositions`() {
        listOf("allOf", "anyOf", "oneOf").forEach { composition ->
            assertThrows<QuerySchemaConflictException> {
                JsonSchemaWalker(
                    schema = JsonSerializer.readTree(
                        """
                        {"properties":{"contact.value":{"$composition":[{"properties":{"phone":{"$MASK_RULE_ATTRIBUTE":"0"}}}]}}}
                        """.trimIndent(),
                    ),
                    maskRuleResolver = { fullMaskRule() },
                ).declaration()
            }
        }
    }

    @Test
    fun `should reject masked descendants behind dynamic array values`() {
        assertThrows<QuerySchemaConflictException> {
            JsonSchemaWalker(
                schema = JsonSerializer.readTree(
                    """
                    {"properties":{"contacts":{"additionalProperties":{"items":{"properties":{"phone":{"$MASK_RULE_ATTRIBUTE":"0"}}}}}}}
                    """.trimIndent(),
                ),
                maskRuleResolver = { fullMaskRule() },
            ).declaration()
        }
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
    fun `should deterministically select conflicting composition metadata`() {
        val (declaration, warnings) = captureMetadataWarnings {
            load(ConflictingCompositionState::class.java)
        }

        declaration.field("state.union.value").title.assert()
            .isEqualTo(DeclarationValue.Set("First"))
        warnings.assert().hasSize(1)
        warnings.single().assert()
            .contains("field=state.union.value")
            .contains("property=title")
            .contains("selected=First")
            .contains("ignored=[Second]")
            .contains("member(stable-value-order)")
    }

    @Test
    fun `should select conflicting container metadata independent of branch order`() {
        val inferences = listOf(ForwardMetadataState::class.java, ReverseMetadataState::class.java).map { type ->
            captureMetadataWarnings { load(type) }
        }

        inferences.forEach { (declaration, warnings) ->
            declaration.field("state.value").let { value ->
                value.title.assert().isEqualTo(DeclarationValue.Set("First title"))
                value.description.assert().isEqualTo(DeclarationValue.Set("First description"))
            }
            warnings.assert().hasSize(2)
            warnings.first().assert()
                .contains("selected=First title")
                .contains("ignored=[Second title]")
                .contains("stable-value-order")
            warnings.last().assert()
                .contains("selected=First description")
                .contains("ignored=[Second description]")
        }
        inferences.map { (_, warnings) -> warnings }.distinct().assert().hasSize(1)
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
    fun `should reject masked recursive descendants`() {
        assertThrows<QuerySchemaConflictException> {
            load(MaskedRecursiveState::class.java)
        }
    }

    @Test
    fun `should reject masked mutually recursive descendants`() {
        assertThrows<QuerySchemaConflictException> {
            load(MutuallyRecursiveMaskedState::class.java)
        }
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
    fun `should reject masked dynamic map values`() {
        assertThrows<QuerySchemaConflictException> {
            load(MaskedDynamicState::class.java)
        }
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

    @Test
    fun `should compile field getter nested collection and composed mask annotations`() {
        val declaration = load(MaskedStructuralState::class.java)

        declaration.field("state.password").assertMaskRule(fullMaskRule())
        declaration.field("state.contacts.phone").assertMaskRule(keepMaskRule())
        declaration.field("state.getterSecret").assertMaskRule(getterKeepMaskRule())
        declaration.field("state.composedSecret").assertMaskRule(composedMaskRule())
    }

    @Test
    fun `should inherit mask from parent Kotlin property`() {
        val rule = load(ChildPropertyMaskedState::class.java)
            .field("state.inheritedSecret")
            .requiredMaskRule()

        rule.strategyType.assert().isEqualTo(PublicClassMaskStrategy::class)
        rule.compiled.mask("secret").assert().isEqualTo("parent-secret")
    }

    @Test
    fun `should inherit mask from interface getter`() {
        val rule = load(InterfaceGetterMaskedState::class.java)
            .field("state.inheritedToken")
            .requiredMaskRule()

        rule.strategyType.assert().isEqualTo(FullMaskStrategy::class)
        rule.compiled.mask("token").assert().isEqualTo("*****")
    }

    @Test
    fun `should inherit Kotlin property getter mask from Java getter`() {
        val declaration = load(JavaGetterMaskedState::class.java)

        listOf("state.inheritedToken", "state.explicitSecret").forEach { field ->
            declaration.field(field)
                .requiredMaskRule()
                .strategyType.assert().isEqualTo(FullMaskStrategy::class)
        }
    }

    @Test
    fun `should include masked non public computed getters visible to Jackson`() {
        val declaration = load(NonPublicComputedGetterState::class.java)

        listOf("state.privateSecret", "state.protectedSecret").forEach { field ->
            declaration.field(field).requiredMaskRule()
                .strategyType.assert().isEqualTo(FullMaskStrategy::class)
        }
    }

    @Test
    fun `should reject conflicting inherited getter masks independent of interface order`() {
        listOf(
            ConflictingInheritedGetterMaskedState::class.java,
            ReversedConflictingInheritedGetterMaskedState::class.java,
        ).forEach { type ->
            assertThrows<QuerySchemaConflictException> {
                load(type)
            }
        }
    }

    @Test
    fun `should use nearer mask when intermediate getter overrides ancestor`() {
        val rule = load(MultiLevelOverrideGetterMaskedState::class.java)
            .field("state.inheritedToken")
            .requiredMaskRule()

        rule.compiled.mask("secret").assert().isEqualTo("se****")
    }

    @Test
    fun `should construct and compile a public zero argument class strategy`() {
        val rule = load(PublicClassStrategyState::class.java)
            .field("state.secret")
            .requiredMaskRule()

        rule.strategyType.assert().isEqualTo(PublicClassMaskStrategy::class)
        rule.compiled.mask("secret").assert().isEqualTo("masked-secret")
    }

    @Test
    fun `should identify the annotation and strategy when mask strategy type is wrong`() {
        val error = assertThrows<QuerySchemaConflictException> {
            load(WrongStrategyMaskState::class.java)
        }

        error.message.assert().contains("WrongStrategyMask").contains("FullMaskStrategy")
    }

    @Test
    fun `should fail closed when mask strategy cannot be constructed`() {
        listOf(
            AbstractMaskStrategyState::class.java,
            privateMaskStrategyStateType(),
        ).forEach { stateType ->
            assertThrows<QuerySchemaConflictException> {
                load(stateType)
            }.message.assert().contains("Unable to instantiate MaskStrategy")
        }
    }

    @Test
    fun `should unwrap and wrap mask strategy constructor failure as conflict`() {
        val error = assertThrows<QuerySchemaConflictException> {
            load(ThrowingMaskStrategyState::class.java)
        }

        error.message.assert().contains("Unable to instantiate MaskStrategy")
        error.cause.assert().isSameAs(constructorMaskFailure)
    }

    @Test
    fun `should wrap mask strategy compile failure as conflict`() {
        val error = assertThrows<QuerySchemaConflictException> {
            load(CompileThrowingMaskStrategyState::class.java)
        }

        error.message.assert().contains("Unable to compile mask annotation")
        error.cause.assert().isSameAs(compileMaskFailure)
    }

    @Test
    fun `should preserve query schema failure from mask strategy compile`() {
        assertThrows<QuerySchemaConflictException> {
            load(CompileQuerySchemaFailureState::class.java)
        }.assert().isSameAs(compileQuerySchemaFailure)
    }

    @Test
    fun `should preserve error from mask strategy compile`() {
        StepVerifier.create(loadPublisher(CompileErrorState::class.java))
            .expectErrorSatisfies { error -> error.assert().isSameAs(compileError) }
            .verify()
    }

    @Test
    fun `should preserve query schema failure from mask strategy constructor`() {
        assertThrows<QuerySchemaConflictException> {
            load(ConstructorQuerySchemaFailureState::class.java)
        }.assert().isSameAs(constructorQuerySchemaFailure)
    }

    @Test
    fun `should preserve error from mask strategy constructor`() {
        StepVerifier.create(loadPublisher(ConstructorErrorState::class.java))
            .expectErrorSatisfies { error -> error.assert().isSameAs(constructorError) }
            .verify()
    }

    @Test
    fun `should reject multiple effective mask annotations on one property`() {
        assertThrownBy<QuerySchemaConflictException> {
            load(ConflictingMaskAnnotationsState::class.java)
        }
    }

    @Test
    fun `should retain a partial alternative branch mask rule`() {
        load(PartiallyMaskedAlternativeState::class.java)
            .field("state.value.shared")
            .assertMaskRule(fullMaskRule(MaskedStringBranch::class.java))
    }

    @Test
    fun `should reject different mask rules across alternative branches`() {
        assertThrownBy<QuerySchemaConflictException> {
            load(DifferentlyMaskedAlternativeState::class.java)
        }
    }

    @Test
    fun `should reject invalid masked targets`() {
        listOf(InvalidMaskedAlternativeState::class.java, InvalidMaskedJvmTypeState::class.java).forEach { type ->
            assertThrownBy<QuerySchemaConflictException> {
                load(type)
            }
        }
    }

    private fun load(type: Class<*>): QuerySchemaDeclaration = loadPublisher(type).single().block()!!

    private fun loadSchema(schema: String): QuerySchemaDeclaration = JsonSchemaWalker(
        schema = JsonSerializer.readTree(schema),
        maskRuleResolver = { fullMaskRule() },
    ).declaration()

    private fun assertTitleResolution(
        schema: String,
        field: String,
        selected: String,
        ignored: List<String>,
        precedence: String,
    ): Pair<QuerySchemaDeclaration, String> {
        val (declaration, warnings) = captureMetadataWarnings { loadSchema(schema) }
        declaration.field(field).title.assert().isEqualTo(DeclarationValue.Set(selected))
        warnings.assert().hasSize(1)
        return declaration to warnings.single().also { warning ->
            warning.assert()
                .contains("field=$field")
                .contains("property=title")
                .contains("selected=$selected")
                .contains("ignored=$ignored")
                .contains(precedence)
        }
    }

    private fun <T> captureMetadataWarnings(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger(JsonSchemaWalker::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply {
            context = logger.loggerContext
            start()
        }
        logger.addAppender(appender)
        return try {
            block() to appender.list
                .filter { it.level == Level.WARN }
                .map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private fun loadPublisher(type: Class<*>): Flux<QuerySchemaDeclaration> =
        JsonQuerySchemaSource(typeResolver = { type }).load(context)

    private fun QuerySchemaDeclaration.field(name: String): QueryFieldDeclaration = fields.getValue(LogicalField(name))

    private fun QueryFieldDeclaration.assertMaskRule(rule: MaskRule) {
        valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        maskRule.assert().isEqualTo(DeclarationValue.Set(rule))
    }

    private fun QueryFieldDeclaration.requiredMaskRule(): MaskRule =
        (maskRule as DeclarationValue.Set).value

    private fun fullMaskRule(type: Class<*> = MaskedStructuralState::class.java): MaskRule {
        val annotation = type.getDeclaredField(
            if (type == MaskedStructuralState::class.java) "password" else "shared",
        ).getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

    private fun keepMaskRule(): MaskRule {
        val annotation = MaskedContact::phone.javaField!!.getAnnotation(KeepMask::class.java)
        return MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
    }

    private fun getterKeepMaskRule(): MaskRule {
        val annotation = MaskedStructuralState::getterSecret.javaGetter!!.getAnnotation(KeepMask::class.java)
        return MaskRule(KeepMaskStrategy::class, annotation, KeepMaskStrategy.compile(annotation))
    }

    private fun composedMaskRule(): MaskRule {
        val annotation = ComposedMask::class.java.getAnnotation(Mask::class.java)
        return MaskRule(FullMaskStrategy::class, annotation, FullMaskStrategy.compile(annotation))
    }

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
