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

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonUnwrapped
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.query.LogicalField
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QueryTemporal
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.api.query.schema.Temporal
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.query.schema.DeclarationValue
import me.ahoo.wow.query.schema.QueryFieldDeclaration
import me.ahoo.wow.query.schema.QuerySchemaConflictException
import me.ahoo.wow.query.schema.QuerySchemaContext
import me.ahoo.wow.query.schema.QuerySchemaDeclaration
import me.ahoo.wow.query.schema.QuerySchemaSourcePriority
import org.junit.jupiter.api.Test
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.ser.std.StdSerializer
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class JsonQuerySchemaSourceTest {
    private val context = QuerySchemaContext(
        MaterializedNamedAggregate("test-context", "test-aggregate"),
        QueryModel.SNAPSHOT,
    )

    @Test
    fun `should use JSON Schema priority`() {
        JsonQuerySchemaSource { StructuralState::class.java }.priority.assert()
            .isEqualTo(QuerySchemaSourcePriority.JSON_SCHEMA)
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
        declaration.field("state.closed").dynamicChildren.assert().isEqualTo(DeclarationValue.Set(false))
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
        JsonQuerySchemaSource { type }.load(context).single().block()!!

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

@Schema(title = "State title", description = "State description")
private data class StructuralState(
    @field:Schema(
        title = "Count title",
        description = "Count description",
        requiredMode = Schema.RequiredMode.REQUIRED,
    )
    val count: Int,
    val ratio: BigDecimal,
    val active: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val optional: String? = null,
    val status: StructuralStatus,
    val address: StructuralAddress,
    val items: List<StructuralItem>,
    val tags: List<String>,
)

private enum class StructuralStatus { ACTIVE, INACTIVE }

private data class StructuralAddress(val city: String)

private data class StructuralItem(val quantity: Int)

private data class JacksonState(
    @field:JsonProperty("display_name")
    val displayName: String,
    @field:JsonProperty("display.name")
    val dottedName: String,
    @field:JsonProperty("display name")
    val spacedName: String,
    @field:JsonProperty("0")
    val numericName: String,
    @field:JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    val secret: String,
    @field:JsonProperty(access = JsonProperty.Access.READ_ONLY)
    val visible: String,
    @get:JsonUnwrapped(prefix = "detail_", suffix = "_value")
    val details: JacksonDetails,
)

private data class JacksonDetails(
    @field:JsonProperty("nested")
    val nested: String,
)

private data class CustomSerializerState(
    val typeValue: TypeCustomSerializedValue,
    @get:JsonSerialize(using = PropertyCustomSerializedValueSerializer::class)
    val propertyValue: PropertyCustomSerializedValue,
)

@JsonSerialize(using = TypeCustomSerializedValueSerializer::class)
private data class TypeCustomSerializedValue(val hidden: String)

private class TypeCustomSerializedValueSerializer : StdSerializer<TypeCustomSerializedValue>(
    TypeCustomSerializedValue::class.java,
) {
    override fun serialize(
        value: TypeCustomSerializedValue,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeString(value.hidden)
    }
}

private data class PropertyCustomSerializedValue(val hidden: String)

private class PropertyCustomSerializedValueSerializer : StdSerializer<PropertyCustomSerializedValue>(
    PropertyCustomSerializedValue::class.java,
) {
    override fun serialize(
        value: PropertyCustomSerializedValue,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        generator.writeString(value.hidden)
    }
}

private data class CompositionState(
    @field:Schema(allOf = [AllOfInherited::class])
    val allOf: AllOfValue,
    val anyOf: AnyOfValue,
    @field:Schema(oneOf = [OneOfFirst::class, OneOfSecond::class])
    val oneOf: OneOfValue,
    val payment: PaymentValue,
)

private data class AllOfValue(val own: String)

private data class AllOfInherited(val inherited: String)

@Schema(anyOf = [AnyOfLeft::class, AnyOfRight::class])
private interface AnyOfValue

private data class AnyOfLeft(val left: String) : AnyOfValue

private data class AnyOfRight(val right: String) : AnyOfValue

private class OneOfValue

private data class OneOfFirst(val first: String)

private data class OneOfSecond(val second: String)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = CardPayment::class, name = "card"),
    JsonSubTypes.Type(value = BankPayment::class, name = "bank"),
)
private interface PaymentValue

private data class CardPayment(val cardNumber: String) : PaymentValue

private data class BankPayment(val account: String) : PaymentValue

private data class RecursiveState(
    val name: String,
    val child: RecursiveState?,
    val children: List<RecursiveState>,
)

private data class DeepLevelOne(val two: DeepLevelTwo)

private data class DeepLevelTwo(val three: DeepLevelThree)

private data class DeepLevelThree(val four: DeepLevelFour)

private data class DeepLevelFour(val five: DeepLevelFive)

private data class DeepLevelFive(val six: DeepLevelSix)

private data class DeepLevelSix(val value: String)

private data class DynamicState(
    val attributes: Map<String, String>,
    val closed: StructuralAddress,
)

private data class NativeTemporalState(
    val date: LocalDate,
    val instant: Instant,
    val instants: List<Instant>,
)

private data class AnnotatedTemporalState(
    @field:JsonProperty("created_at")
    @field:QueryTemporal(TimeUnit.SECONDS)
    val createdAt: Long,
    @field:QueryTemporal
    val timestamps: List<Long>,
)

private data class InvalidTemporalState(
    @field:QueryTemporal
    val createdAt: String,
)
