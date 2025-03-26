package me.ahoo.wow.schema

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
import me.ahoo.wow.api.annotation.CommandRoute
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
import me.ahoo.wow.modeling.DefaultAggregateId
import me.ahoo.wow.modeling.state.SimpleStateAggregate
import me.ahoo.wow.modeling.state.StateAggregate
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.tck.mock.MockStateAggregate
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.joda.money.CurrencyUnit
import org.joda.money.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

class JsonSchemaGeneratorTest {
    private val jsonSchemaGenerator = JsonSchemaGenerator()

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
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerate")
    fun generate(interfaceType: Class<*>, implType: Class<*>) {
        val schema = jsonSchemaGenerator.generate(implType)
        assertThat(schema, equalTo(WowSchemaLoader.load(interfaceType)))
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerateTypeParameter")
    fun generateTypeParameter(interfaceType: Class<*>, typeParameter: Class<*>, resourceName: String) {
        val schema = jsonSchemaGenerator.generate(interfaceType, typeParameter)
        assertThat(schema.toPrettyString(), equalTo(WowSchemaLoader.loadAsString(resourceName)))
    }

    @Test
    fun ignoreCommandPathRouteVariable() {
        val schema = jsonSchemaGenerator.generate(Patch::class.java).asJsonSchema()
        assertThat(schema.getProperties(), nullValue())
    }

    @Test
    fun ignoreCommandHeaderRouteVariable() {
        val schema = jsonSchemaGenerator.generate(Header::class.java).asJsonSchema()
        assertThat(schema.getProperties(), nullValue())
    }

    @Test
    fun notIgnoreCommandPathRouteVariable() {
        val jsonSchemaGenerator = JsonSchemaGenerator(setOf())
        val schema = jsonSchemaGenerator.generate(Patch::class.java).asJsonSchema()
        assertThat(schema.getProperties(), notNullValue())
    }

    @Test
    fun enum() {
        val jsonSchemaGenerator = JsonSchemaGenerator()
        val schema = jsonSchemaGenerator.generate(CommandStage::class.java).asJsonSchema()
        assertThat(schema.getProperties(), nullValue())
    }

    @Test
    fun openAPI() {
        val jacksonModule: Module = JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED)
        val jakartaModule = JakartaValidationModule()
        val openApiModule: Module = Swagger2Module()
        val wowModule = WowModule(setOf(WowOption.KOTLIN, WowOption.WOW_NAMING_STRATEGY))
        val schemaGeneratorConfigBuilder = SchemaGeneratorConfigBuilder(
            JsonSerializer,
            SchemaVersion.DRAFT_2020_12,
            OptionPreset.PLAIN_JSON
        ).with(jacksonModule)
            .with(jakartaModule)
            .with(openApiModule)
            .with(wowModule)
            .with(Option.EXTRA_OPEN_API_FORMAT_VALUES)
            .with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
            .with(Option.PLAIN_DEFINITION_KEYS)

        val schemaGenerator = SchemaGenerator(schemaGeneratorConfigBuilder.build())

        val openAPISchemaBuilder = schemaGenerator.buildMultipleSchemaDefinitions()
        val schema = openAPISchemaBuilder.createSchemaReference(CreateOrder::class.java).asJsonSchema()
        val componentsSchemas = openAPISchemaBuilder.collectDefinitions("components/schemas")
        assertThat(schema.get(SchemaKeyword.TAG_REF), notNullValue())
        assertThat(componentsSchemas.size(), equalTo(3))
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
        val schema = jsonSchemaGenerator.generate(KotlinData::class.java)
        val nullableFieldType = schema.get("properties").get("nullableField").get("type")
        assertThat(nullableFieldType.isArray, equalTo(true))
        assertThat(nullableFieldType.get(0).textValue(), equalTo("string"))
        assertThat(nullableFieldType.get(1).textValue(), equalTo("null"))
        val readOnlyField = schema.get("properties").get("readOnlyField")
        assertThat(readOnlyField.get("readOnly").booleanValue(), equalTo(true))
        val readOnlyGetter = schema.get("properties").get("readOnlyGetter")
        assertThat(readOnlyGetter.get("readOnly").booleanValue(), equalTo(true))
        val required = schema.get("required")
        assertThat(required.isArray, equalTo(true))
        assertThat(required.get(0).textValue(), equalTo("field"))
        assertThat(required.get(1).textValue(), equalTo("nullableField"))
    }

    @Test
    fun javaType() {
        val jsonSchemaGenerator = JsonSchemaGenerator(setOf(WowOption.KOTLIN))
        jsonSchemaGenerator.generate(CosIdGeneratorStat::class.java)
    }

    @Test
    fun kotlin_ignore() {
        val jsonSchemaGenerator = JsonSchemaGenerator(setOf())
        val schema = jsonSchemaGenerator.generate(KotlinData::class.java)
        val type = schema.get("properties").get("nullableField").get("type")
        assertThat(type.isArray, equalTo(false))
        assertThat(type.textValue(), equalTo("string"))
    }

    @Test
    fun schema() {
        val schema = jsonSchemaGenerator.generate(SchemaData::class.java)
        val nullableFieldType = schema.get("properties").get("nullableField").get("type")
        assertThat(nullableFieldType.isArray, equalTo(true))
        assertThat(nullableFieldType.get(0).textValue(), equalTo("string"))
        assertThat(nullableFieldType.get(1).textValue(), equalTo("null"))
        val readOnlyField = schema.get("properties").get("readOnlyField")
        assertThat(readOnlyField.get("readOnly").booleanValue(), equalTo(true))
        val required = schema.get("required")
        assertThat(required.isArray, equalTo(true))
        assertThat(required.get(0).textValue(), equalTo("nullableField"))
        assertThat(required.get(1).textValue(), equalTo("requiredField"))
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
        val nullableField: String?,
        @field:Schema(accessMode = Schema.AccessMode.READ_ONLY)
        val readOnlyField: String?,
        @field:Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        val writeOnlyField: String?,
        @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        val requiredField: String?,
    )
}
