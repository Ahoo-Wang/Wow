package me.ahoo.wow.schema.openapi

import com.fasterxml.classmate.TypeResolver
import com.github.victools.jsonschema.generator.Option
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.command.wait.SimpleWaitSignal
import me.ahoo.wow.schema.AnnotationFixture
import me.ahoo.wow.schema.ChangeTestName
import me.ahoo.wow.schema.CreateTestAggregate
import me.ahoo.wow.schema.SchemaGeneratorBuilder
import me.ahoo.wow.schema.TestState
import me.ahoo.wow.schema.TreeNodeFixture
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.node.ObjectNode

class OpenAPISchemaBuilderTest {

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
    fun `should build condition schema with recursive ref`() {
        val definitionPath = "${'$'}defs"
        val openAPISchemaBuilder = OpenAPISchemaBuilder(definitionPath = definitionPath)
        openAPISchemaBuilder.generateSchema(Condition::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        val conditionSchema = componentsSchemas["wow.api.query.Condition"]
        val childrenItem = conditionSchema?.properties[Condition::children.name]?.items
        childrenItem.assert().isNotNull()
        childrenItem?.`$ref`.assert().startsWith("#/$definitionPath")
    }

    @Test
    fun `should build tree node schema with children items`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        openAPISchemaBuilder.generateSchema(TreeNodeFixture::class.java)
        val componentsSchemas = openAPISchemaBuilder.build()
        val schema = componentsSchemas["wow.schema.TreeNodeFixture"]
        val childrenItem = schema?.properties[TreeNodeFixture::children.name]?.items
        childrenItem.assert().isNotNull()
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

    @Test
    @Suppress("DEPRECATION")
    fun `should expose schema reference compatibility type`() {
        val openAPISchemaBuilder = OpenAPISchemaBuilder()
        val resolvedType = openAPISchemaBuilder.resolveType(CreateTestAggregate::class.java)
        val node = "{}".toObject<ObjectNode>()
        val schema = io.swagger.v3.oas.models.media.Schema<Any>()

        val schemaReference = openAPISchemaBuilder.SchemaReference(resolvedType, schema, node)

        schemaReference.type.assert().isSameAs(resolvedType)
        schemaReference.schema.assert().isSameAs(schema)
        schemaReference.node.assert().isSameAs(node)
    }
}
