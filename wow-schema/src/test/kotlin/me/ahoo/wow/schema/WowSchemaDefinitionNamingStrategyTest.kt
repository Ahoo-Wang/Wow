package me.ahoo.wow.schema

import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.schema.WowSchemaDefinitionNamingStrategy.toSchemaName
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@Schema(name = "SchemaDefinitionNamingStrategyTest")
class WowSchemaDefinitionNamingStrategyTest {
    companion object {
        @JvmStatic
        fun parametersForToSchemaName(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(AggregateId::class.java, "wow.AggregateId"),
                Arguments.of(CreateOrder::class.java, "example.order.CreateOrder"),
                Arguments.of(Any::class.java, "Object"),
                Arguments.of(WowSchemaDefinitionNamingStrategyTest::class.java, "SchemaDefinitionNamingStrategyTest"),
                Arguments.of(ExampleService::class.java, "example.ExampleService")
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForToSchemaName")
    fun toSchemaName(clazz: Class<*>, expectedSchemaName: String) {
        val schemaName = clazz.toSchemaName()
        assertThat(schemaName, equalTo(expectedSchemaName))
    }
}
