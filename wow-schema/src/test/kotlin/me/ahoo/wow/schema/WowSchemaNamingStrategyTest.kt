package me.ahoo.wow.schema

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.generator.TypeContext
import com.github.victools.jsonschema.generator.impl.TypeContextFactory
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.modeling.AggregateId
import me.ahoo.wow.api.query.MaterializedSnapshot
import me.ahoo.wow.api.query.PagedList
import me.ahoo.wow.example.api.ExampleService
import me.ahoo.wow.example.api.order.CreateOrder
import me.ahoo.wow.example.domain.cart.CartState
import me.ahoo.wow.example.domain.order.OrderState
import me.ahoo.wow.schema.WowSchemaNamingStrategy.toSchemaName
import me.ahoo.wow.serialization.JsonSerializer
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.MatcherAssert.*
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
        private val typeContext: TypeContext = TypeContextFactory.createDefaultTypeContext(generatorConfig)

        @JvmStatic
        fun parametersForToSchemaName(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(typeContext.resolve(AggregateId::class.java), "wow.api.modeling.AggregateId"),
                Arguments.of(typeContext.resolve(CreateOrder::class.java), "example.order.CreateOrder"),
                Arguments.of(typeContext.resolve(Any::class.java), null),
                Arguments.of(
                    typeContext.resolve(WowSchemaNamingStrategyTest::class.java),
                    "wow.SchemaDefinitionNamingStrategyTest"
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
                    "example.WowExampleOrderStateMaterializedSnapshotPagedList"
                ),
                Arguments.of(
                    typeContext.resolve(
                        PagedList::class.java,
                        typeContext.resolve(
                            MaterializedSnapshot::class.java,
                            CartState::class.java
                        )
                    ),
                    "example.CartStateMaterializedSnapshotPagedList"
                ),
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForToSchemaName")
    fun toSchemaName(type: ResolvedType, expectedSchemaName: String?) {
        val schemaName = type.toSchemaName()
        assertThat(schemaName, equalTo(expectedSchemaName))
    }
}
