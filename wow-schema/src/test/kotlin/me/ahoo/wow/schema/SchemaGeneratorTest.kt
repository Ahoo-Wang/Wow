package me.ahoo.wow.schema

import com.fasterxml.classmate.TypeResolver
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaKeyword
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule
import com.github.victools.jsonschema.module.jakarta.validation.JakartaValidationModule
import com.github.victools.jsonschema.module.swagger2.Swagger2Module
import me.ahoo.cosid.stat.generator.CosIdGeneratorStat
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.Identifier
import me.ahoo.wow.api.annotation.AggregateRoot
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import java.util.stream.Stream

class SchemaGeneratorTest {
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
                Arguments.of(DomainEventStream::class.java, AggregatedDomainEventStream::class.java),
                Arguments.of(CharRange::class.java, CharRange::class.java),
                Arguments.of(IntRange::class.java, IntRange::class.java),
                Arguments.of(LongRange::class.java, LongRange::class.java),
            )
        }

        @JvmStatic
        fun parametersForGenerateTypeParameter(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(
                    CommandMessage::class.java,
                    CreateTestAggregate::class.java,
                    "CreateTestAggregateCommandMessage"
                ),
                Arguments.of(
                    DomainEvent::class.java,
                    TestAggregateCreated::class.java,
                    "TestAggregateCreatedDomainEvent"
                ),
                Arguments.of(
                    StateAggregate::class.java,
                    MockStateAggregate::class.java,
                    "MockStateAggregate"
                ),
                Arguments.of(
                    Snapshot::class.java,
                    MockStateAggregate::class.java,
                    "MockStateAggregateSnapshot"
                ),
                Arguments.of(
                    StateEvent::class.java,
                    MockStateAggregate::class.java,
                    "MockStateAggregateStateEvent"
                ),
                Arguments.of(
                    AggregatedDomainEventStream::class.java,
                    TestAggregate::class.java,
                    "TestAggregateAggregatedDomainEventStream"
                ),
                Arguments.of(
                    AggregatedDomainEventStream::class.java,
                    MockEmptyAggregate::class.java,
                    "MockEmptyAggregateAggregatedDomainEventStream"
                ),
                Arguments.of(
                    AggregatedFields::class.java,
                    TestAggregate::class.java,
                    "TestAggregateAggregatedFields"
                ),
                Arguments.of(
                    AggregatedCondition::class.java,
                    TestAggregate::class.java,
                    "TestAggregateAggregatedCondition"
                ),
                Arguments.of(
                    AggregatedListQuery::class.java,
                    TestAggregate::class.java,
                    "TestAggregateAggregatedListQuery"
                ),
                Arguments.of(
                    AggregatedPagedQuery::class.java,
                    TestAggregate::class.java,
                    "TestAggregateAggregatedPagedQuery"
                ),
                Arguments.of(
                    AggregatedSingleQuery::class.java,
                    TestAggregate::class.java,
                    "TestAggregateAggregatedSingleQuery"
                ),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerate")
    fun `should generate schema matching expected`(interfaceType: Class<*>, implType: Class<*>) {
        val schema = jsonSchemaGenerator.generateSchema(implType)
        schema.assert().isEqualTo(WowSchemaLoader.load(interfaceType))
    }

    @ParameterizedTest
    @MethodSource("parametersForGenerateTypeParameter")
    fun `should generate type parameterized schema`(
        interfaceType: Class<*>,
        typeParameter: Class<*>,
        resourceName: String
    ) {
        val schema = jsonSchemaGenerator.generateSchema(interfaceType, typeParameter)
        schema.toPrettyString().assert().isEqualTo(WowSchemaLoader.loadAsString(resourceName))
    }

    @Test
    fun `should ignore command path route variable in schema`() {
        val schema = jsonSchemaGenerator.generateSchema(CommandRouteFixture::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun `should ignore command header route variable in schema`() {
        val schema = jsonSchemaGenerator.generateSchema(HeaderRouteFixture::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun `should not ignore path route variable when wow module is empty`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().wowModule(WowModule(setOf())).build()
        val schema = jsonSchemaGenerator.generateSchema(CommandRouteFixture::class.java).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate enum schema without properties`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(CommandStage::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun `should generate aggregated condition schema for test aggregate`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedCondition::class.java,
            TestAggregate::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate aggregated condition schema for any type`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedCondition::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate aggregated list query schema for any type`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val schema = jsonSchemaGenerator.generateSchema(
            AggregatedListQuery::class.java
        ).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate open api schema with component definitions`() {
        val jacksonModule: Module = JacksonSchemaModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED)
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
        val schema = openAPISchemaBuilder.createSchemaReference(CreateTestAggregate::class.java).asJsonSchema()
        val componentsSchemas = openAPISchemaBuilder.collectDefinitions("components/schemas")
        schema.get<JsonNode>(SchemaKeyword.TAG_REF).assert().isNotNull()
        componentsSchemas.assert().hasSize(3)
    }

    @Test
    fun `should generate kotlin data class schema with nullable and readonly fields`() {
        val schema = jsonSchemaGenerator.generateSchema(KotlinFixture::class.java)
        val nullableFieldAnyOfType = schema.get("properties").get("nullableField").get("anyOf")
        nullableFieldAnyOfType.isArray.assert().isTrue()
        nullableFieldAnyOfType.get(0).get("type").stringValue().assert().isEqualTo("null")
        nullableFieldAnyOfType.get(1).get("type").stringValue().assert().isEqualTo("string")
        val readOnlyField = schema.get("properties").get("readOnlyField")
        readOnlyField.get("readOnly").booleanValue().assert().isTrue()
        val readOnlyGetter = schema.get("properties").get("readOnlyGetter")
        readOnlyGetter.get("readOnly").booleanValue().assert().isTrue()
        val required = schema.get("required")
        required.isArray.assert().isTrue()
        required.get(0).stringValue().assert().isEqualTo("field")
        required.get(1).stringValue().assert().isEqualTo("nullableField")
    }

    @Test
    fun `should generate polymorphic schema with anyOf`() {
        val schema = jsonSchemaGenerator.generateSchema(PolymorphicFixture::class.java)
        schema.get("anyOf").assert().isNotNull()
    }

    @Test
    fun `should generate schema for java type`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        jsonSchemaGenerator.generateSchema(CosIdGeneratorStat::class.java)
    }

    @Test
    fun `should generate schema for array type`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder().build()
        val arrayType = TypeResolver().arrayType(AnnotationFixture::class.java)
        val arrayTypeSchema = jsonSchemaGenerator.generateSchema(arrayType)
        arrayTypeSchema.get("type").asString().assert().isEqualTo("array")
    }

    @Test
    fun `should generate schema with annotations`() {
        val schema = jsonSchemaGenerator.generateSchema(AnnotationFixture::class.java)
        val nullableFieldNode = schema.get("properties").get("nullableField")
        val nullableFieldAnyOfType = nullableFieldNode.get("anyOf")
        nullableFieldAnyOfType.isArray.assert().isTrue()
        nullableFieldAnyOfType.get(0).get("type").stringValue().assert().isEqualTo("null")
        nullableFieldAnyOfType.get(1).get("type").stringValue().assert().isEqualTo("string")
        nullableFieldNode.get("title").stringValue().assert().isEqualTo("titleField")
        nullableFieldNode.get("description").stringValue().assert().isEqualTo("descField")
        val readOnlyField = schema.get("properties").get("readOnlyField")
        readOnlyField.get("readOnly").booleanValue().assert().isTrue()
        val required = schema.get("required")
        required.isArray.assert().isTrue()
        required.get(0).stringValue().assert().isEqualTo("nullableField")
        required.get(1).stringValue().assert().isEqualTo("requiredField")
        schema.get("properties").get("ignoredProp").assert().isNull()
        schema.get("properties").get("hiddenProp").assert().isNull()
        val getterNode = schema.get("properties").get("getterProp")
        getterNode.assert().isNotNull()
        getterNode.get("readOnly").booleanValue().assert().isTrue()
        getterNode.get("description").assert().isNotNull()
    }

    @Test
    fun `should handle is-prefixed properties with wow jackson module`() {
        val schema = jsonSchemaGenerator.generateSchema(IsPrefixFixture::class.java)
        val isOwnerNode = schema.get("properties").get("isOwner")
        isOwnerNode.assert().isNotNull()
        val isMissingNode = schema.get("properties").get("isMissing")
        isMissingNode.assert().isNotNull()
    }

    @Test
    fun `should handle is-prefixed properties with standard jackson module`() {
        val jsonSchemaGenerator = SchemaGeneratorBuilder()
            .openapi31(true)
            .schemaVersion(SchemaVersion.DRAFT_2020_12)
            .optionPreset(OptionPreset.PLAIN_JSON)
            .jacksonModule(
                JacksonSchemaModule(
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
        val schema = jsonSchemaGenerator.generateSchema(IsPrefixFixture::class.java)
        val isOwnerNode = schema.get("properties").get("isOwner")
        isOwnerNode.assert().isNotNull()
        val isMissingNode = schema.get("properties").get("isMissing")
        isMissingNode.assert().isNotNull()
    }
}

@AggregateRoot
class MockEmptyAggregate(override val id: String) : Identifier
