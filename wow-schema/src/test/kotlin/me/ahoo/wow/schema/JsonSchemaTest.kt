package me.ahoo.wow.schema

import com.fasterxml.classmate.TypeResolver
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test
import org.springframework.http.codec.ServerSentEvent

class JsonSchemaTest {
    private val jsonSchemaGenerator = SchemaGeneratorBuilder().schemaVersion(
        SchemaVersion.DRAFT_2020_12
    ).wowModule(WowModule(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))).build()

    @Test
    fun get() {
        val schema = jsonSchemaGenerator.generateSchema(CreateOrder::class.java)
            .asJsonSchema(schemaVersion = SchemaVersion.DRAFT_2020_12)
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun serverSentEvent() {
        val resolvedType = TypeResolver().resolve(ServerSentEvent::class.java)
        val schema = jsonSchemaGenerator.generateSchema(resolvedType)
            .asJsonSchema(schemaVersion = SchemaVersion.DRAFT_2020_12)
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun serverSentEventData() {
        val resolvedType =
            TypeResolver().resolve(ServerSentEvent::class.java, AggregatedFieldPathsTest.DemoState::class.java)
        val schema = jsonSchemaGenerator.generateSchema(resolvedType)
            .asJsonSchema(schemaVersion = SchemaVersion.DRAFT_2020_12)
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun requiredGetPropertiesIfEmpty() {
        val emptySchema = "{}".toObject<ObjectNode>().asJsonSchema()
        assertThrownBy<IllegalArgumentException> {
            emptySchema.requiredGetProperties()
        }
    }
}
