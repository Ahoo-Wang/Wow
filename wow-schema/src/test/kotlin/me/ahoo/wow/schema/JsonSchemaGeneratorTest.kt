package me.ahoo.wow.schema

import me.ahoo.wow.api.annotation.CommandRoute
import me.ahoo.wow.command.SimpleCommandMessage
import me.ahoo.wow.example.api.order.CreateOrder
import org.hamcrest.MatcherAssert.*
import org.hamcrest.Matchers.*
import org.junit.jupiter.api.Test

class JsonSchemaGeneratorTest {
    val jsonSchemaGenerator = JsonSchemaGenerator(setOf(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE))

    @Test
    fun generate() {
        val schema = jsonSchemaGenerator.generate(SimpleCommandMessage::class.java, CreateOrder::class.java)
        println(schema.toPrettyString())
    }

    @Test
    fun patch() {
        val schema = jsonSchemaGenerator.generate(Patch::class.java)
        assertThat(schema.get("properties"), nullValue())
        println(schema.toPrettyString())
    }

    data class Patch(
        @field:CommandRoute.PathVariable
        val field: String,
        @CommandRoute.PathVariable
        val property: String,
        @get:CommandRoute.PathVariable
        val getter: String
    )
}
