package me.ahoo.wow.schema.naming

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
import me.ahoo.wow.schema.CreateTestAggregate
import me.ahoo.wow.schema.OuterFixture
import me.ahoo.wow.schema.TestAggregate
import me.ahoo.wow.schema.TestState
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
        private const val defaultSchemaNamePrefix = "SchemaDefinitionNamingStrategyTest."
        private val typeContext: TypeContext = TypeContextFactory.createDefaultTypeContext(generatorConfig)

        @Suppress("LongMethod")
        @JvmStatic
        fun parametersForToSchemaName(): Stream<Arguments> {
            return Stream.of(
                Arguments.of(typeContext.resolve(AggregateId::class.java), "wow.api.modeling.AggregateId"),
                Arguments.of(typeContext.resolve(CreateTestAggregate::class.java), "wow.schema.CreateTestAggregate"),
                Arguments.of(typeContext.resolve(Any::class.java), "${defaultSchemaNamePrefix}Object"),
                Arguments.of(
                    typeContext.resolve(WowSchemaNamingStrategyTest::class.java),
                    "wow.schema.SchemaDefinitionNamingStrategyTest"
                ),
                Arguments.of(
                    typeContext.resolve(
                        PagedList::class.java,
                        typeContext.resolve(
                            MaterializedSnapshot::class.java,
                            TestState::class.java
                        )
                    ),
                    "wow.schema.TestStateMaterializedSnapshotPagedList"
                ),
                Arguments.of(
                    typeContext.resolve(
                        arrayOf(mockk<CreateTestAggregate>()).javaClass
                    ),
                    "wow.schema.CreateTestAggregateArray"
                ),
                Arguments.of(
                    typeContext.resolve(
                        Map::class.java,
                        String::class.java,
                        Object::class.java
                    ),
                    "SchemaDefinitionNamingStrategyTest.StringObjectMap"
                ),
                Arguments.of(
                    typeContext.resolve(
                        OuterFixture.InnerFixture::class.java
                    ),
                    "wow.schema.OuterFixture.InnerFixture"
                ),
                Arguments.of(
                    typeContext.resolve(
                        OuterFixture.StaticNestedFixture::class.java
                    ),
                    "wow.schema.OuterFixture.StaticNestedFixture"
                ),
                Arguments.of(
                    typeContext.resolve(
                        AggregatedFields::class.java,
                        TestAggregate::class.java
                    ),
                    "wow.schema.TestAggregateAggregatedFields"
                )
            )
        }
    }

    @ParameterizedTest
    @MethodSource("parametersForToSchemaName")
    fun `should resolve schema name from type`(type: ResolvedType, expectedSchemaName: String) {
        val schemaName = type.toSchemaName(defaultSchemaNamePrefix)
        schemaName.assert().isEqualTo(expectedSchemaName)
    }
}
