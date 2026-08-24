package me.ahoo.wow.schema.openapi

import com.fasterxml.classmate.TypeResolver
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.Option
import io.swagger.v3.core.util.ObjectMapperFactory
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.api.query.PagedQuery
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.schema.AnnotationFixture
import me.ahoo.wow.schema.ChangeTestName
import me.ahoo.wow.schema.CreateTestAggregate
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.TestState
import me.ahoo.wow.schema.TreeNodeFixture
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

class OpenAPISchemaBuilderTest {

    @Test
    fun `should rebase embedded schema references to its component`() {
        val schemaName = "wow.api.query.FilterExpression"
        val componentPath = "#/components/schemas/$schemaName"
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        openAPISchemaBuilder.generateSchema(FilterExpression::class.java)

        val schema = requireNotNull(openAPISchemaBuilder.build()[schemaName])
        val schemaNode = ObjectMapperFactory.createJson31().valueToTree<com.fasterxml.jackson.databind.JsonNode>(schema)
        val references = schemaNode.findValues("\$ref").map { it.asText() }

        schemaNode["\$id"].assert().isNull()
        schemaNode["\$ref"].asText().assert().isEqualTo("$componentPath/definitions/filterExpression")
        references.assert().contains("$componentPath/definitions/matchAll")
        references.filter { it.startsWith("#/definitions/") }.assert().isEmpty()
    }

    @Test
    fun `should build query schema with recursive filter reference`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        val reference = openAPISchemaBuilder.generateSchema(PagedQuery::class.java)

        val schemas = openAPISchemaBuilder.build()

        reference.`$ref`.assert().isEqualTo("#/components/schemas/wow.api.query.PagedQuery")
        schemas["wow.api.query.PagedQuery"]?.properties?.get("filter")?.`$ref`
            .assert().isEqualTo("#/components/schemas/wow.api.query.FilterExpression")
    }

    @Test
    fun `should rebase root self reference to its component`() {
        val schemaNode = JsonMapper.builder().build().createObjectNode()
            .put("\$id", "urn:self-reference")
            .put("\$ref", "#")

        val (componentPath, schema) = buildCustomSchema(schemaNode)

        schema.`$id`.assert().isNull()
        schema.`$ref`.assert().isEqualTo(componentPath)
    }

    @Test
    fun `should preserve schema with nested resource`() {
        val schemaNode = JsonMapper.builder().build().createObjectNode()
            .put("\$id", "urn:root")
            .put("\$ref", "#/definitions/node")
        schemaNode.putObject("definitions").putObject("node")
            .put("\$id", "urn:nested")
            .put("\$ref", "#")

        val (_, schema) = buildCustomSchema(schemaNode)

        schema.`$id`.assert().isEqualTo("urn:root")
        schema.`$ref`.assert().isEqualTo("#/definitions/node")
    }

    @Test
    fun `should build open api schema with component references`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        openAPISchemaBuilder.inline.assert().isFalse()
        val stringSchema = openAPISchemaBuilder.generateSchema(String::class.java)
        stringSchema.types.assert().contains("string")
        val createSchema = openAPISchemaBuilder.generateSchema(CreateTestAggregate::class.java)
        createSchema.`$ref`.assert().isNull()
        val changeNameSchema = openAPISchemaBuilder.generateSchema(ChangeTestName::class.java)
        changeNameSchema.`$ref`.assert().isNull()
        val testStateSnapshotSchema = openAPISchemaBuilder.generateSchema(
            MaterializedSnapshot::class.java,
            TestState::class.java
        )
        testStateSnapshotSchema.`$ref`.assert().isNull()
        val testStateSnapshotPagedListSchema = openAPISchemaBuilder.generateSchema(
            PagedList::class.java,
            openAPISchemaBuilder.resolveType(
                MaterializedSnapshot::class.java,
                TestState::class.java
            )
        )
        testStateSnapshotPagedListSchema.`$ref`.assert().isNull()
        val componentsSchemas = openAPISchemaBuilder.build()
        createSchema.`$ref`.assert().isNotNull()
        changeNameSchema.`$ref`.assert().isNotNull()
        componentsSchemas.assert().hasSize(9)
    }

    @Test
    fun `should build inline schema without component references`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder(
            schemaGeneratorBuilder = SchemaGeneratorBuilder().customizer {
                it.with(Option.INLINE_ALL_SCHEMAS)
            }
        )
        openAPISchemaBuilder.inline.assert().isTrue()
        val createSchema = openAPISchemaBuilder.generateSchema(CreateTestAggregate::class.java)
        createSchema.`$ref`.assert().isNull()
        val changeNameSchema = openAPISchemaBuilder.generateSchema(ChangeTestName::class.java)
        changeNameSchema.`$ref`.assert().isNull()
        val testStateSnapshotSchema = openAPISchemaBuilder.generateSchema(
            MaterializedSnapshot::class.java,
            TestState::class.java
        )
        testStateSnapshotSchema.`$ref`.assert().isNull()
        val testStateSnapshotPagedListSchema = openAPISchemaBuilder.generateSchema(
            PagedList::class.java,
            openAPISchemaBuilder.resolveType(
                MaterializedSnapshot::class.java,
                TestState::class.java
            )
        )
        testStateSnapshotPagedListSchema.`$ref`.assert().isNull()
        val componentsSchemas = openAPISchemaBuilder.build()
        componentsSchemas.assert().isEmpty()
    }

    @Test
    fun `should generate schema for server sent event type`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder(
            schemaGeneratorBuilder = SchemaGeneratorBuilder().customizer {
                it.with(Option.INLINE_ALL_SCHEMAS)
            }
        )
        openAPISchemaBuilder.inline.assert().isTrue()
        val schema = openAPISchemaBuilder.generateSchema(ServerSentEvent::class.java)

        val componentsSchemas = openAPISchemaBuilder.build()
        componentsSchemas.assert().isEmpty()
    }

    @Test
    fun `should build schema for wait signal`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        openAPISchemaBuilder.inline.assert().isFalse()
        openAPISchemaBuilder.generateSchema(SimpleWaitSignal::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        componentsSchemas.assert().hasSize(7)
    }

    @Test
    fun `should build tree node schema with recursive ref`() {
        val definitionPath = "${'$'}defs"
        val openAPISchemaBuilder = OpenAPISchemaBuilder(definitionPath = definitionPath)
        openAPISchemaBuilder.generateSchema(TreeNodeFixture::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        val schema = componentsSchemas["wow.schema.TreeNodeFixture"]
        val childrenItem = schema?.properties[TreeNodeFixture::children.name]?.items
        childrenItem.assert().isNotNull()
        childrenItem?.`$ref`.assert().startsWith("#/$definitionPath")
    }

    @Test
    fun `should generate schema for array type`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        val arrayType = TypeResolver().arrayType(AnnotationFixture::class.java)
        val arrayTypeSchema = openAPISchemaBuilder.generateSchema(arrayType)
        val componentsSchemas = openAPISchemaBuilder.build()
        val schema = componentsSchemas["wow.schema.AnnotationFixture"]
        arrayTypeSchema.types.assert().contains("array")
    }

    private fun buildCustomSchema(schemaNode: ObjectNode): Pair<String, Schema<*>> {
        val schemaGeneratorBuilder = SchemaGeneratorBuilder().customizer { config ->
            config.with(Option.DEFINITIONS_FOR_ALL_OBJECTS)
            config.forTypesInGeneral().withCustomDefinitionProvider { javaType, _ ->
                if (javaType.erasedType == SelfReferentialSchema::class.java) CustomDefinition(schemaNode) else null
            }
        }
        val openAPISchemaBuilder = OpenAPISchemaBuilder(schemaGeneratorBuilder = schemaGeneratorBuilder)
        val reference = openAPISchemaBuilder.generateSchema(SelfReferentialSchema::class.java)
        val schemas = openAPISchemaBuilder.build()
        val componentPath = requireNotNull(reference.`$ref`)
        return componentPath to requireNotNull(schemas[componentPath.substringAfterLast('/')])
    }

    private class SelfReferentialSchema
}
