package me.ahoo.wow.schema

import com.fasterxml.classmate.TypeResolver
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.Version
import me.ahoo.wow.api.query.SmallMaterializedSnapshot
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent
import tools.jackson.databind.node.ObjectNode

class JsonSchemaTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().schemaVersion(
        SchemaVersion.DRAFT_2020_12
    ).wowModule(WowModule(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))).build()

    @Test
    fun `should get schema properties`() {
        val schema = jsonSchemaGenerator.generateSchema(CreateTestAggregate::class.java)
            .asJsonSchema(schemaVersion = SchemaVersion.DRAFT_2020_12)
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate schema for server sent event`() {
        val resolvedType = TypeResolver().resolve(ServerSentEvent::class.java)
        val schema = jsonSchemaGenerator.generateSchema(resolvedType)
            .asJsonSchema(schemaVersion = SchemaVersion.DRAFT_2020_12)
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should generate schema for parameterized server sent event`() {
        val resolvedType =
            TypeResolver().resolve(ServerSentEvent::class.java, TestState::class.java)
        val schema = jsonSchemaGenerator.generateSchema(resolvedType)
            .asJsonSchema(schemaVersion = SchemaVersion.DRAFT_2020_12)
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should throw when getting required properties on empty schema`() {
        val emptySchema = "{}".toObject<ObjectNode>().asJsonSchema()
        assertThrownBy<IllegalArgumentException> {
            emptySchema.requiredGetProperties()
        }
    }

    @Test
    fun `should generate schema for small materialized snapshot`() {
        val schema = jsonSchemaGenerator.generateSchema(
            SmallMaterializedSnapshot::class.java,
            TestState::class.java
        )
        val jsonSchema = schema.asJsonSchema(SchemaVersion.DRAFT_2020_12)
        jsonSchema.getProperties().assert().isNotNull()
        jsonSchema.getProperties()!!.get(Version::version.name).assert().isNotNull()
        jsonSchema.getProperties()!!.get(Version::initialized.name).assert().isNull()
        jsonSchema.getProperties()!!.get(Version::isInitialVersion.name).assert().isNull()
    }
}
