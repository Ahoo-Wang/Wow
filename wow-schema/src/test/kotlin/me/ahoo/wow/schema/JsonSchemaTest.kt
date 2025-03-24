package me.ahoo.wow.schema

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.serialization.toObject
import org.hamcrest.CoreMatchers.notNullValue
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class JsonSchemaTest {
    private val jsonSchemaGenerator = JsonSchemaGenerator(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))

    @Test
    fun get() {
        val schema = jsonSchemaGenerator.generate(CreateOrder::class.java)
            .asJsonSchema(schemaVersion = SchemaVersion.DRAFT_2020_12)
        assertThat(schema.getProperties(), notNullValue())
    }

    @Test
    fun requiredGetPropertiesIfEmpty() {
        val emptySchema = "{}".toObject<ObjectNode>().asJsonSchema()
        Assertions.assertThrows(IllegalArgumentException::class.java) {
            emptySchema.requiredGetProperties()
        }
    }
}
