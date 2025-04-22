package me.ahoo.wow.schema

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.serialization.toObject
import org.junit.jupiter.api.Test

class JsonSchemaTest {
    private val jsonSchemaGenerator = JsonSchemaGenerator(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))

    @Test
    fun get() {
        val schema = jsonSchemaGenerator.generate(CreateOrder::class.java)
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
