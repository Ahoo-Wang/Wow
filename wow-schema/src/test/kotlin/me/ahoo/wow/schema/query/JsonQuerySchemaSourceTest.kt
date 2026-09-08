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
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.mask.FullMaskStrategy
import me.ahoo.wow.api.query.mask.KeepMask
import me.ahoo.wow.api.query.mask.KeepMaskStrategy
import me.ahoo.wow.api.query.mask.Mask
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryValueKind
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
import me.ahoo.wow.query.schema.QueryPathSegment
import me.ahoo.wow.query.schema.QueryPathTemplate
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

        declaration.propertyPaths().assert()
            .contains(QueryField("body.body.added.productId"))
            .contains(QueryField("body.body.added.quantity"))
            .contains(QueryField("body.body.productIds"))
            .contains(QueryField("body.body.changed.productId"))
            .contains(QueryField("body.body.changed.quantity"))
        declaration.field("body.body.added.productId").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        declaration.field("body.body.productIds").kind.assert()
            .isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
        declaration.field("body.body.changed.quantity").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        declaration.field("body.body").kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.UNION))
        declaration.nodes("body.body.added").single().required.assert().isEqualTo(DeclarationValue.Set(true))
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
                    return Mono.just(QueryModelSchema(QueryModel.EVENT_STREAM, emptySet(), logicalSchema, emptyMap()))
                }
            },
        )

        provider.schema().block()!!

        checkNotNull(
            resolved.get().value(
                QueryPathTemplate(listOf(QueryPathSegment.Property("body"), QueryPathSegment.Property("bodyType")))
            )!!.enumValues
        )
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
            .propertyPaths().assert().contains(QueryField("state.items.productId"))
    }

    @Test
    fun `should cache the same type independently by query model`() {
        val source = JsonQuerySchemaSource(typeResolver = { Cart::class.java })

        source.load(context).single().block()!!
        val eventStream = source.load(context.copy(model = QueryModel.EVENT_STREAM)).single().block()!!

        eventStream.propertyPaths().assert().contains(QueryField("body.body.added.productId"))
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
                        QueryField("state") to QueryFieldDeclaration(
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

        declaration.field("state").title.assert().isEqualTo(DeclarationValue.Set("State title"))
        declaration.field("state").description.assert().isEqualTo(DeclarationValue.Set("State description"))
        declaration.field("state").kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.OBJECT))
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
        declaration.field("state.optional").let { value ->
            value.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.UNION))
            value.nullable.assert().isEqualTo(DeclarationValue.Set(true))
            value.required.assert().isEqualTo(DeclarationValue.Set(false))
            value.alternatives.or(emptyList()).map { it.kind.or(QueryValueKind.UNKNOWN) }
                .assert().containsExactly(QueryValueKind.NULL, QueryValueKind.SCALAR)
            value.alternatives.or(emptyList()).last().valueTypes.assert()
                .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        }
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
            items.items.or(null)!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            items.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
        }
        declaration.field("state.items.quantity").valueTypes.assert()
            .isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
        declaration.field("state.tags").let { tags ->
            tags.items.or(null)!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
            tags.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
        }
    }

    @Test
    fun `should follow Jackson property shape and reject illegal logical segments`() {
        val declaration = load(JacksonState::class.java)

        declaration.propertyPaths().assert()
            .contains(QueryField("state.display_name"))
            .contains(QueryField("state.detail_nested_value"))
            .contains(QueryField("state.visible"))
            .doesNotContain(QueryField("state.secret"))
        declaration.propertyPaths().any { it.path in setOf("state.display.name", "state.display name", "state.0") }
            .assert().isFalse()
        declaration.propertyPaths().any { it.path.startsWith("state.details") }.assert().isFalse()
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
            load(MaskedInvalidQueryFieldState::class.java)
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
        declaration.propertyPaths().any { it.path.endsWith(".hidden") }.assert().isFalse()
    }

    @Test
    fun `should traverse ref and all schema composition branches`() {
        val declaration = load(CompositionState::class.java)

        declaration.propertyPaths().assert()
            .contains(QueryField("state.allOf.inherited"))
            .contains(QueryField("state.allOf.own"))
            .contains(QueryField("state.anyOf.left"))
            .contains(QueryField("state.anyOf.right"))
            .contains(QueryField("state.oneOf.first"))
            .contains(QueryField("state.oneOf.second"))
            .contains(QueryField("state.payment.kind"))
            .contains(QueryField("state.payment.cardNumber"))
            .contains(QueryField("state.payment.account"))
        listOf("state.allOf.inherited", "state.allOf.own").forEach { field ->
            declaration.field(field).required.assert().isEqualTo(DeclarationValue.Set(true))
        }
        listOf(
            "state.anyOf.left",
            "state.anyOf.right",
            "state.oneOf.first",
            "state.oneOf.second",
        ).forEach { field ->
            declaration.nodes(field).single().required.assert().isEqualTo(DeclarationValue.Set(true))
        }
    }

    @Test
    fun `should merge repeated composition fields independent of branch order`() {
        val declaration = load(RepeatedCompositionState::class.java)
        val forward = declaration.nodes("state.forward.value")
        val reverse = declaration.nodes("state.reverse.value")
        val expectedTypes =
            setOf(
                DeclarationValue.Set(setOf(QueryValueType.STRING)),
                DeclarationValue.Set(setOf(QueryValueType.INTEGER))
            )
        forward.map { it.valueTypes }.toSet().assert().isEqualTo(expectedTypes)
        reverse.map { it.valueTypes }.toSet().assert().isEqualTo(expectedTypes)
        forward.forEach { it.required.assert().isEqualTo(DeclarationValue.Set(true)) }
    }

    @Test
    fun `should mark a shared alternative field optional when only some branches require it`() {
        load(PartiallyRequiredCompositionState::class.java)
            .nodes("state.value.shared").map { it.required }.toSet().assert()
            .isEqualTo(setOf(DeclarationValue.Set(false), DeclarationValue.Set(true)))
    }

    @Test
    fun `should reject disjoint value types for the same allOf field`() {
        assertThrows<QuerySchemaConflictException> {
            load(ConflictingAllOfValueTypesState::class.java)
        }
    }

    @Test
    fun `should retain the common null domain in allOf intersections`() {
        listOf(
            """{"type":["string","null"],"allOf":[{"type":"null"}]}""",
            """{"type":"null","allOf":[{"type":["string","null"]}]}""",
            """{"allOf":[{"type":["string","null"]},{"type":["integer","null"]}]}""",
            """{"allOf":[{"type":["integer","null"]},{"type":["string","null"]}]}""",
            """{"type":["object","null"],"allOf":[{"type":["array","null"],"items":{"type":"string"}}]}""",
            """{"type":["string","null"],"allOf":[{"anyOf":[{"type":"null"},{"type":"integer"}]}]}""",
        ).forEach { value ->
            val result = loadSchema("""{"properties":{"value":$value},"required":["value"]}""").field("state.value")
            result.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.NULL))
            result.nullable.assert().isEqualTo(DeclarationValue.Set(true))
            result.required.assert().isEqualTo(DeclarationValue.Set(true))
            result.valueTypes.or(emptySet()).assert().isEmpty()
            result.properties.or(emptyMap()).assert().isEmpty()
            result.items.or(null).assert().isNull()
        }
    }

    @Test
    fun `null only intersections retain metadata and intersect enums`() {
        val value = loadSchema(
            """{"properties":{"value":{"title":"Null value","description":"Only null survives","type":["string","null"],"enum":[null,"A"],"allOf":[{"type":"null","enum":[null]}]}}}"""
        ).field("state.value")
        value.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.NULL))
        value.title.assert().isEqualTo(DeclarationValue.Set("Null value"))
        value.description.assert().isEqualTo(DeclarationValue.Set("Only null survives"))
        value.enumValues.or(null).assert().isEqualTo(listOf(JsonSerializer.readTree("null")))
    }

    @Test
    fun `nullable intersections still reject empty domains and lost mask constraints`() {
        listOf(
            """{"allOf":[{"type":"string"},{"type":["integer","null"]}]}""",
            """{"allOf":[{"type":["string","null"],"enum":["A"]},{"type":"null"}]}""",
            """{"type":["string","null"],"allOf":[{"type":"null"}],"enum":["A"]}""",
            """{"allOf":[{"type":["string","null"],"$MASK_RULE_ATTRIBUTE":"0"},{"type":"null"}]}""",
        ).forEach { value ->
            assertThrows<QuerySchemaConflictException> {
                loadSchema("""{"properties":{"value":$value}}""")
            }
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
    fun `should retain distinct enum constraints in union branches`() {
        listOf(ForwardEnumState::class.java, ReverseEnumState::class.java).forEach { type ->
            val value = load(type).field("state.value")
            value.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.UNION))
            value.alternatives.or(emptyList()).map { it.enumValues }.distinct().assert().hasSize(2)
        }
    }

    @Test
    fun `should retain equal container metadata and semantic type`() {
        val declaration = load(EqualContainerMetadataState::class.java)

        declaration.field("state.metadata").let { metadata ->
            metadata.title.assert().isEqualTo(DeclarationValue.Set("Shared title"))
            metadata.description.assert().isEqualTo(DeclarationValue.Set("Shared description"))
        }
        declaration.field("state.temporal").alternatives.or(emptyList()).forEach {
            it.semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        }
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

        declaration.propertyPaths().assert()
            .contains(QueryField("state.child"))
            .contains(QueryField("state.children"))
        declaration.field("state.child").alternatives.or(emptyList()).map { it.kind.or(QueryValueKind.UNKNOWN) }
            .assert().containsExactly(QueryValueKind.NULL, QueryValueKind.UNKNOWN)
        declaration.field("state.children").kind.assert()
            .isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
        declaration.propertyPaths().any {
            it.path.startsWith("state.child.") || it.path.startsWith("state.children.")
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
        load(DeepLevelOne::class.java).propertyPaths().assert()
            .contains(QueryField("state.two.three.four.five.six.value"))
    }

    @Test
    fun `should mark object additional properties as dynamic`() {
        val declaration = load(DynamicState::class.java)

        declaration.field("state.attributes").let { attributes ->
            attributes.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            attributes.additionalProperties.or(
                null
            )!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        }
        declaration.field("state.attributeGroups").let { attributeGroups ->
            attributeGroups.items.or(
                null
            )!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.OBJECT)))
            attributeGroups.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
            attributeGroups.items.or(
                null
            )!!.additionalProperties.or(
                null
            )!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.STRING)))
        }
        declaration.field("state.closed").additionalProperties.or(null).assert().isNull()
    }

    @Test
    fun `should retain masked descendants in dynamic map values`() {
        load(MaskedDynamicState::class.java).field("state.contacts").additionalProperties.or(null)!!
            .properties.or(emptyMap()).getValue("phone").assertMaskRule(keepMaskRule())
    }

    @Test
    fun `should detect object and explicit true additional properties`() {
        mapOf(
            """{"additionalProperties":{"type":"string"}}""" to true,
            """{"additionalProperties":true}""" to true,
            """{"additionalProperties":false}""" to false,
        ).forEach { (schema, expected) ->
            (loadSchema(schema).field("state").additionalProperties.or(null) != null).assert().isEqualTo(expected)
        }
    }

    @Test
    fun `should infer native date formats`() {
        val declaration = load(NativeTemporalState::class.java)

        listOf("state.date", "state.instant").forEach { field ->
            declaration.field(field).semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        }
        declaration.field(
            "state.instants"
        ).items.or(null)!!.semanticType.assert().isEqualTo(DeclarationValue.Set(Temporal.Date))
        declaration.field("state.instants").kind.assert()
            .isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
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
            timestamps.items.or(
                null
            )!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
            timestamps.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
            timestamps.items.or(null)!!.semanticType.assert().isEqualTo(
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
            .nodes("state.value.shared").single { it.maskRule is DeclarationValue.Set }
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
        listOf(InvalidMaskedJvmTypeState::class.java).forEach { type ->
            assertThrownBy<QuerySchemaConflictException> {
                load(type)
            }
        }
    }

    @Test
    fun `scalar array and null alternatives retain full branch facts`() {
        val value = loadSchema(
            """{"properties":{"value":{"anyOf":[{"type":"string"},{"type":"array","items":{"type":["integer","null"]}},{"type":"null"}]}}}"""
        )
            .field("state.value")
        val alternatives = value.alternatives.or(emptyList())
        alternatives.map { it.kind }.assert().isEqualTo(
            listOf(
                DeclarationValue.Set(QueryValueKind.SCALAR),
                DeclarationValue.Set(QueryValueKind.ARRAY),
                DeclarationValue.Set(QueryValueKind.NULL),
            )
        )
        val array = alternatives[1]
        array.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        array.items.or(null)!!.nullable.assert().isEqualTo(DeclarationValue.Set(true))
        array.valueTypes.assert().isEqualTo(DeclarationValue.Set(emptySet<QueryValueType>()))
    }

    @Test
    fun `map array object mask tree preserves every nullable boundary`() {
        val value = loadSchema(
            """{"properties":{"addresses":{"type":"object","additionalProperties":{"type":["array","null"],"items":{"type":["object","null"],"properties":{"email":{"type":"string","$MASK_RULE_ATTRIBUTE":"0"}}}}}}}"""
        )
            .field("state.addresses")
        value.nullable.assert().isEqualTo(DeclarationValue.Set(false))
        val array = value.additionalProperties.or(null)!!
        array.nullable.assert().isEqualTo(DeclarationValue.Set(true))
        val item = array.items.or(null)!!
        item.nullable.assert().isEqualTo(DeclarationValue.Set(true))
        item.properties.or(emptyMap()).getValue("email").assertMaskRule(fullMaskRule())
    }

    @Test
    fun `a protected string union branch coexists with an unprotected integer branch`() {
        val values = load(InvalidMaskedAlternativeState::class.java).nodes("state.value.shared")
        values.single {
            it.valueTypes == DeclarationValue.Set(setOf(QueryValueType.STRING))
        }.assertMaskRule(fullMaskRule(MaskedStringBranch::class.java))
        values.single {
            it.valueTypes == DeclarationValue.Set(setOf(QueryValueType.INTEGER))
        }.maskRule.assert().isEqualTo(DeclarationValue.Unset)
    }

    @Test
    fun `allOf narrows a union without flattening the surviving shape`() {
        val value = loadSchema(
            """{"properties":{"value":{"allOf":[{"anyOf":[{"type":"string"},{"type":"array","items":{"type":"integer"}}]},{"type":"array"}]}}}"""
        )
            .field("state.value")
        value.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.ARRAY))
        value.items.or(null)!!.valueTypes.assert().isEqualTo(DeclarationValue.Set(setOf(QueryValueType.INTEGER)))
    }

    private fun load(type: Class<*>): QuerySchemaDeclaration = loadPublisher(type).single().block()!!

    @Test
    fun `local enum intersects allOf enum instead of overriding it`() {
        val value = loadSchema(
            """{"properties":{"value":{"type":"string","enum":["a","b"],"allOf":[{"enum":["b","c"]}]}}}"""
        ).field("state.value")

        value.enumValues.or(null)!!.map { it.stringValue() }.assert().containsExactly("b")
    }

    @Test
    fun `allOf enum branches retain their nonempty intersection`() {
        val value = loadSchema(
            """{"properties":{"value":{"allOf":[{"type":"string","enum":["a","b"]},{"type":"string","enum":["b","c"]}]}}}"""
        ).field("state.value")

        value.enumValues.or(null)!!.map { it.stringValue() }.assert().containsExactly("b")
    }

    @Test
    fun `disjoint local and allOf enums reject the declaration`() {
        assertThrows<QuerySchemaConflictException> {
            loadSchema(
                """{"properties":{"value":{"type":"string","enum":["a"],"allOf":[{"enum":["b"]}]}}}"""
            )
        }
    }

    @Test
    fun `enum intersection removes impossible union branches`() {
        listOf(
            """{"allOf":[{"anyOf":[{"type":"string","enum":["a"]},{"type":"string","enum":["b","c"]}]},{"type":"string","enum":["b","d"]}]}""",
            """{"anyOf":[{"type":"string","enum":["a"]},{"type":"string","enum":["b","c"]}],"enum":["b","d"]}""",
            """{"allOf":[{"anyOf":[{"type":"string","enum":["a"]},{"type":"string","enum":["b"]}]},{"anyOf":[{"type":"string","enum":["b"]},{"type":"string","enum":["c"]}]}]}""",
        ).forEach { definition ->
            val value = loadSchema("""{"properties":{"value":$definition}}""").field("state.value")
            value.kind.assert().isEqualTo(DeclarationValue.Set(QueryValueKind.SCALAR))
            value.enumValues.or(null)!!.map { it.stringValue() }.assert().containsExactly("b")
        }
    }

    private fun loadSchema(schema: String): QuerySchemaDeclaration = JsonSchemaWalker(
        schema = JsonSerializer.readTree(schema),
        maskRuleResolver = { if (it == "1") keepMaskRule() else fullMaskRule() },
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

    private fun QuerySchemaDeclaration.nodes(name: String): List<QueryFieldDeclaration> {
        val root = fields.entries.filter {
            name == it.key.path || name.startsWith("${it.key.path}.")
        }.maxBy { it.key.path.length }
        val suffix = name.removePrefix(root.key.path).removePrefix(".").takeIf { it.isNotEmpty() }?.split('.').orEmpty()
        fun visit(value: QueryFieldDeclaration, path: List<String>): List<QueryFieldDeclaration> {
            if (path.isEmpty()) return listOf(value)
            if (value.kind.or(QueryValueKind.UNKNOWN) == QueryValueKind.UNION) {
                return value.alternatives.or(emptyList()).flatMap {
                    visit(it, path)
                }
            }
            value.items.or(null)?.let { return visit(it, path) }
            val child = value.properties.or(emptyMap())[path.first()] ?: value.additionalProperties.or(null) ?: return emptyList()
            return visit(child, path.drop(1))
        }
        return visit(root.value, suffix)
    }

    private fun QuerySchemaDeclaration.field(name: String): QueryFieldDeclaration = nodes(name).distinct().single()

    private fun QuerySchemaDeclaration.propertyPaths(): Set<QueryField> = buildSet {
        fun visit(value: QueryFieldDeclaration, field: QueryField) {
            add(field)
            value.properties.or(emptyMap()).forEach { (name, child) -> visit(child, QueryField("${field.path}.$name")) }
            value.items.or(null)?.let { visit(it, field) }
            value.alternatives.or(emptyList()).forEach { visit(it, field) }
        }
        fields.forEach { (field, value) -> visit(value, field) }
    }

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
    ) = QueryFieldDeclaration(
        title = DeclarationValue.Set(title),
        description = DeclarationValue.Set(description),
        enumValues = DeclarationValue.Set(null),
        valueTypes = DeclarationValue.Set(valueTypes),
        nullable = DeclarationValue.Set(nullable),
        required = DeclarationValue.Set(required),
        kind = DeclarationValue.Set(
            if (valueTypes == setOf(QueryValueType.OBJECT)) QueryValueKind.OBJECT else QueryValueKind.SCALAR
        ),
        semanticType = DeclarationValue.Set(null),
    )
}
