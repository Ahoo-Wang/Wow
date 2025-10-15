package me.ahoo.wow.schema

import com.fasterxml.classmate.TypeResolver
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaKeyword
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.cosid.stat.generator.CosIdGeneratorStat
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.api.annotation.Description
import me.ahoo.wow.api.annotation.Summary
import me.ahoo.wow.api.command.CommandMessage
import me.ahoo.wow.api.event.DomainEvent
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.event.DomainEventStream
import me.ahoo.wow.event.SimpleDomainEvent
import me.ahoo.wow.event.SimpleDomainEventStream
import me.ahoo.wow.eventsourcing.snapshot.SimpleSnapshot
import me.ahoo.wow.eventsourcing.snapshot.Snapshot
import me.ahoo.wow.eventsourcing.state.StateEvent
import me.ahoo.wow.eventsourcing.state.StateEventData
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.api.order.OrderCreated
import me.ahoo.wow.example.domain.cart.Cart
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.modeling.DefaultAggregateId
import me.ahoo.wow.modeling.state.SimpleStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.kotlin.KotlinModule
import me.ahoo.wow.schema.naming.SchemaNamingModule
import me.ahoo.wow.schema.typed.AggregatedDomainEventStream
import me.ahoo.wow.schema.typed.AggregatedFields
import me.ahoo.wow.schema.typed.query.AggregatedCondition
import me.ahoo.wow.schema.typed.query.AggregatedListQuery
import me.ahoo.wow.schema.typed.query.AggregatedPagedQuery
import me.ahoo.wow.schema.typed.query.AggregatedSingleQuery
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.joda.money.CurrencyUnit
import org.joda.money.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class JsonSchemaGeneratorTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder()
        .openapi31(true)
        .schemaVersion(SchemaVersion.DRAFT_2020_12)
        .optionPreset(OptionPreset.PLAIN_JSON)
        .customizer {
        }
        .build()

    companion object {
        @JvmStatic
        fun parametersForGenerate(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(AggregateId::class.java, AggregateId::class.java),
                Arguments.of(AggregateId::class.java, DefaultAggregateId::class.java),
                Arguments.of(CommandMessage::class.java, CommandMessage::class.java),
                Arguments.of(CommandMessage::class.java, SimpleCommandMessage::class.java),
                Arguments.of(DomainEvent::class.java, DomainEvent::class.java),
                Arguments.of(DomainEvent::class.java, SimpleDomainEvent::class.java),
                Arguments.of(DomainEventStream::class.java, DomainEventStream::class.java),
                Arguments.of(DomainEventStream::class.java, SimpleDomainEventStream::class.java),
                Arguments.of(StateAggregate::class.java, StateAggregate::class.java),
                Arguments.of(StateAggregate::class.java, SimpleStateAggregate::class.java),
                Arguments.of(Snapshot::class.java, Snapshot::class.java),
                Arguments.of(Snapshot::class.java, SimpleSnapshot::class.java),
                Arguments.of(StateEvent::class.java, StateEvent::class.java),
                Arguments.of(StateEvent::class.java, StateEventData::class.java),
                Arguments.of(CurrencyUnit::class.java, CurrencyUnit::class.java),
                Arguments.of(Money::class.java, Money::class.java),
                Arguments.of(DomainEventStream::class.java, AggregatedDomainEventStream::class.java),
            )
        }

        @JvmStatic
        fun parametersForGenerateTypeParameter(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(CommandMessage::class.java, CreateOrder::class.java, "CreateOrderCommandMessage"),
                Arguments.of(DomainEvent::class.java, OrderCreated::class.java, "OrderCreatedDomainEvent"),
                Arguments.of(StateAggregate::class.java, MockStateAggregate::class.java, "MockStateAggregate"),
                Arguments.of(Snapshot::class.java, MockStateAggregate::class.java, "MockStateAggregateSnapshot"),
                Arguments.of(StateEvent::class.java, MockStateAggregate::class.java, "MockStateAggregateStateEvent"),
                Arguments.of(
                    AggregatedDomainEventStream::class.java,
                    Cart::class.java,
                    "CartAggregatedDomainEventStream"
                ),
                Arguments.of(
                    AggregatedDomainEventStream::class.java,
                    MockEmptyAggregate::class.java,
                    "EmptyAggregatedDomainEventStream"
                ),
                Arguments.of(
                    AggregatedFields::class.java,
                    Order::class.java,
                    "OrderAggregatedFields"
                ),
                Arguments.of(
                    AggregatedCondition::class.java,
                    Order::class.java,
                    "OrderAggregatedCondition"
                ),
                Arguments.of(
                    AggregatedListQuery::class.java,
                    Order::class.java,
                    "OrderAggregatedListQuery"
                ),
                Arguments.of(
                    AggregatedPagedQuery::class.java,
                    Order::class.java,
                    "OrderAggregatedPagedQuery"
                ),
                Arguments.of(
                    AggregatedSingleQuery::class.java,
                    Order::class.java,
                    "OrderAggregatedSingleQuery"
                ),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerate")
    fun generate(interfaceType: Class<*>, implType: Class<*>) {
        val schema = jsonSchemaGenerator.generateSchema(implType)
        schema.assert().isEqualTo(WowSchemaLoader.load(interfaceType))
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerateTypeParameter")
    fun generateTypeParameter(interfaceType: Class<*>, typeParameter: Class<*>, resourceName: String) {
        val schema = jsonSchemaGenerator.generateSchema(interfaceType, typeParameter)
        schema.toPrettyString().assert().isEqualTo(WowSchemaLoader.loadAsString(resourceName))
    }

    @Test
    fun ignoreCommandPathRouteVariable() {
        val schema = jsonSchemaGenerator.generateSchema(Patch::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun ignoreCommandHeaderRouteVariable() {
        val schema = jsonSchemaGenerator.generateSchema(Header::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun notIgnoreCommandPathRouteVariable() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().wowModule(WowModule(setOf())).build()
        val schema = jsonSchemaGenerator.generateSchema(Patch::class.java).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun enum() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(CommandStage::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun aggregatedCondition() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedCondition::class.java,
            Order::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun aggregatedConditionForAny() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedCondition::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun aggregatedListQueryForAny() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedListQuery::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun openAPI() {
        val jacksonModule: Module = JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED)
        val jakartaModule = JakartaValidationModule()
        val openApiModule: Module = Swagger2Module()
        val wowModule = WowModule()
        val schemaGeneratorConfigBuilder = SchemaGeneratorConfigBuilder(
            JsonSerializer,
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        ).with(jacksonModule)
            .with(jakartaModule)
            .with(openApiModule)
            .with(wowModule)
            .with(SchemaNamingModule())
            .with(KotlinModule())
            .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
            .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
            .with(Option.PLAIN_DEFINITION_KEYS)

        val schemaGenerator = SchemaGenerator(schemaGeneratorConfigBuilder.build())

        val openAPISchemaBuilder = schemaGenerator.buildMultipleSchemaDefinitions()
        val schema = openAPISchemaBuilder.createSchemaReference(CreateOrder::class.java).asJsonSchema()
        val componentsSchemas = openAPISchemaBuilder.collectDefinitions("components/schemas")
        schema.get<JsonNode>(SchemaKeyword.TAG_REF).assert().isNotNull()
        componentsSchemas.assert().hasSize(3)
    }

    data class Patch(
        @field:CommandRoute.PathVariable
        val field: String,
        @CommandRoute.PathVariable
        val property: String,
        @get:CommandRoute.PathVariable
        val getter: String
    )

    data class Header(
        @field:CommandRoute.HeaderVariable
        val field: String,
        @CommandRoute.HeaderVariable
        val property: String,
        @get:CommandRoute.HeaderVariable
        val getter: String
    )

    @Test
    fun kotlin() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinData::class.java)
        val nullableFieldType = schema.get("properties").get("nullableField").get("type")
        nullableFieldType.isArray.assert().isTrue()
        nullableFieldType.get(0).textValue().assert().isEqualTo("string")
        nullableFieldType.get(1).textValue().assert().isEqualTo("null")
        val readOnlyField = schema.get("properties").get("readOnlyField")
        readOnlyField.get("readOnly").booleanValue().assert().isTrue()
        val readOnlyGetter = schema.get("properties").get("readOnlyGetter")
        readOnlyGetter.get("readOnly").booleanValue().assert().isTrue
        val required = schema.get("required")
        required.isArray.assert().isTrue()
        required.get(0).textValue().assert().isEqualTo("field")
        required.get(1).textValue().assert().isEqualTo("nullableField")
    }

    @Test
    fun schemaPolymorphic() {
        val schema = jsonSchemaGenerator.generateSchema(PolymorphicConfig::class.java)
        schema.get("anyOf").assert().isNotNull()
    }

    @Test
    fun javaType() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        jsonSchemaGenerator.generateSchema(CosIdGeneratorStat::class.java)
    }

    @Test
    fun arrayType() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val arrayType = TypeResolver().arrayType(SchemaData::class.java)
        val arrayTypeSchema = jsonSchemaGenerator.generateSchema(arrayType)
        arrayTypeSchema.get("type").asText().assert().isEqualTo("array")
    }

    @Test
    fun schema() {
        val schema = jsonSchemaGenerator.generateSchema(SchemaData::class.java)
        val nullableFieldNode = schema.get("properties").get("nullableField")
        val nullableFieldType = nullableFieldNode.get("type")
        nullableFieldType.isArray.assert().isTrue()
        nullableFieldType.get(0).textValue().assert().isEqualTo("string")
        nullableFieldType.get(1).textValue().assert().isEqualTo("null")
        nullableFieldNode.get("title").textValue().assert().isEqualTo("testSummary")
        nullableFieldNode.get("description").textValue().assert().isEqualTo("testDescription")
        val readOnlyField = schema.get("properties").get("readOnlyField")
        readOnlyField.get("readOnly").booleanValue().assert().isTrue()
        val required = schema.get("required")
        required.isArray.assert().isTrue()
        required.get(0).textValue().assert().isEqualTo("nullableField")
        required.get(1).textValue().assert().isEqualTo("requiredField")
        schema.get("properties").get("ignoreProperty").assert().isNull()
        schema.get("properties").get("ignoreSchemaProperty").assert().isNull()
    }

    @Test
    fun schemaStartIsFieldUseWowJackson() {
        val schema = jsonSchemaGenerator.generateSchema(StartIsProperty::class.java)
        val isOwnerNode = schema.get("properties").get("isOwner")
        isOwnerNode.assert().isNotNull()
        val isMissingNode = schema.get("properties").get("isMissing")
        isMissingNode.assert().isNotNull()
    }

    @Test
    fun schemaStartIsFieldUseJackson() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder()
            .openapi31(true)
            .schemaVersion(SchemaVersion.DRAFT_2020_12)
            .optionPreset(OptionPreset.PLAIN_JSON)
            .jacksonModule(
                JacksonModule(
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONVALUE,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY,
                    JacksonOption.RESPECT_JSONPROPERTY_ORDER,
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.INLINE_TRANSFORMED_SUBTYPES
                )
            )
            .customizer {
            }
            .build()
        val schema = jsonSchemaGenerator.generateSchema(StartIsProperty::class.java)
        val isOwnerNode = schema.get("properties").get("isOwner")
        isOwnerNode.assert().isNotNull()
        val isMissingNode = schema.get("properties").get("isMissing")
        isMissingNode.assert().isNull()
    }

    @Suppress("UnusedPrivateProperty")
    data class KotlinData(
        val field: String,
        val nullableField: String?,
        val defaultField: String = "default",
    ) {
        private var writeOnlyField: String = "writeOnly"

        val readOnlyField: String = "readOnly"
        val readOnlyGetter: String
            get() = "readOnlyGetter"
        val readOnlyFieldByLazy: String by lazy { "readOnlyByLazy" }
    }

    data class SchemaData(
        @field:Schema(nullable = true)
        @Summary("testSummary")
        @Description("testDescription")
        val nullableField: String?,
        @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
        val readOnlyField: String?,
        @field:Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        val writeOnlyField: String?,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        val requiredField: String?,

    ) {
        val getter: String
            get() = ""

        @get:JsonIgnore(false)
        val getterJsonIgnoreFalse: String
            get() = ""

        @get:Schema(hidden = false)
        val getterSchemaHiddenFalse: String
            get() = ""

        @get:JsonIgnore
        val ignoreProperty: String
            get() = ""

        @get:Schema(hidden = true)
        val ignoreSchemaProperty: String
            get() = ""
    }

    data class StartIsProperty(
        /**
         * WARN: JacksonModule
         *
         * shouldIgnoreField
         *
         * // other kinds of field ignorals are handled implicitly, i.e. are only available by way of being absent
         */
        @field:JsonProperty("isOwner")
        val isOwner: Boolean,
        val isMissing: Boolean
    )
}

@AggregateRoot
class MockEmptyAggregate(override val id: String) : Identifier
