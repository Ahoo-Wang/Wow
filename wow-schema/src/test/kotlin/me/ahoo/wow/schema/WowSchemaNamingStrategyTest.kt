package me.ahoo.wow.schema

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.generator.TypeContext
import com.github.victools.jsonschema.generator.impl.TypeContextFactory
import io.mockk.mockk
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.configuration.BoundedContext
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.Order
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.schema.naming.WowSchemaNamingStrategy.Companion.toSchemaName
import me.ahoo.wow.schema.typed.AggregatedFields
import me.ahoo.wow.serialization.JsonSerializer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

@Schema(name = "SchemaDefinitionNamingStrategyTest")
class WowSchemaNamingStrategyTest {
    companion object {
        private val generatorConfig: SchemaGeneratorConfig =
            SchemaGeneratorConfigBuilder(JsonSerializer, SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
                .build()
        private const val defaultSchemaNamePrefix = "WowSchemaNamingStrategyTest."
        private val typeContext: TypeContext = TypeContextFactory.createDefaultTypeContext(generatorConfig)

        @Suppress("LongMethod")
        @JvmStatic
        fun parametersForToSchemaName(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(typeContext.resolve(AggregateId::class.java), "wow.api.modeling.AggregateId"),
                Arguments.of(typeContext.resolve(CreateOrder::class.java), "example.order.CreateOrder"),
                Arguments.of(typeContext.resolve(Any::class.java), "${defaultSchemaNamePrefix}Object"),
                Arguments.of(
                    typeContext.resolve(WowSchemaNamingStrategyTest::class.java),
                    "wow.schema.SchemaDefinitionNamingStrategyTest"
                ),
                Arguments.of(typeContext.resolve(ExampleService::class.java), "example.ExampleService"),
                Arguments.of(
                    typeContext.resolve(
                        PagedList::class.java,
                        typeContext.resolve(
                            MaterializedSnapshot::class.java,
                            OrderState::class.java
                        )
                    ),
                    "example.order.WowExampleOrderStateMaterializedSnapshotPagedList"
                ),
                Arguments.of(
                    typeContext.resolve(
                        PagedList::class.java,
                        typeContext.resolve(
                            MaterializedSnapshot::class.java,
                            CartState::class.java
                        )
                    ),
                    "example.cart.CartStateMaterializedSnapshotPagedList"
                ),
                Arguments.of(
                    typeContext.resolve(
                        arrayOf(mockk<CreateOrder>()).javaClass
                    ),
                    "example.order.CreateOrderArray"
                ),
                Arguments.of(
                    typeContext.resolve(
                        Map::class.java,
                        String::class.java,
                        Object::class.java
                    ),
                    "WowSchemaNamingStrategyTest.StringObjectMap"
                ),
                Arguments.of(
                    typeContext.resolve(
                        Map::class.java,
                        String::class.java,
                        BoundedContext::class.java
                    ),
                    "wow.configuration.StringBoundedContextMap"
                ),
                Arguments.of(
                    typeContext.resolve(
                        Outer.Inner::class.java
                    ),
                    "wow.schema.Outer.Inner"
                ),
                Arguments.of(
                    typeContext.resolve(
                        Outer.StaticNested::class.java
                    ),
                    "wow.schema.Outer.StaticNested"
                ),
                Arguments.of(
                    typeContext.resolve(
                        AggregatedFields::class.java,
                        Order::class.java
                    ),
                    "example.order.OrderAggregatedFields"
                )
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForToSchemaName")
    fun toSchemaName(type: ResolvedType, expectedSchemaName: String) {
        val schemaName = type.toSchemaName(defaultSchemaNamePrefix)
        schemaName.assert().isEqualTo(expectedSchemaName)
    }
}

class Outer {
    inner class Inner // 非静态内部类
    class StaticNested // 静态嵌套类
}
