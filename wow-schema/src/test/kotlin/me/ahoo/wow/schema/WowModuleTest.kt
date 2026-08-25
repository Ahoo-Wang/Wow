/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.wow.schema

import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.RelativeTimeFilter
import me.ahoo.wow.schema.JsonSchema.Companion.asJsonSchema
import me.ahoo.wow.schema.kotlin.KotlinModule
import org.junit.jupiter.api.Test

class WowModuleTest {

    @Test
    fun `should provide filter expression schema when configured directly`() {
        val config = SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_7, OptionPreset.PLAIN_JSON)
            .with(KotlinModule())
            .with(WowModule())
            .build()

        val schema = SchemaGenerator(config).generateSchema(FilterExpression::class.java)

        schema.assert().isEqualTo(WowSchemaLoader.load(FilterExpression::class.java))
    }

    @Test
    fun `should expose typed logical fields for filter subtypes`() {
        val schema = SchemaGeneratorBuilder().build().generateSchema(RelativeTimeFilter::class.java)

        schema.path("properties").path("field").isMissingNode.assert().isFalse()
        schema.path("properties").path("datePattern").isMissingNode.assert().isTrue()
    }

    @Test
    fun `should include IGNORE_COMMAND_ROUTE_VARIABLE in ALL options`() {
        WowOption.ALL.assert().contains(WowOption.IGNORE_COMMAND_ROUTE_VARIABLE)
    }

    @Test
    fun `should ignore command route path variable by default`() {
        val generator = SchemaGeneratorBuilder().build()
        val schema = generator.generateSchema(CommandRouteFixture::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun `should ignore command route header variable by default`() {
        val generator = SchemaGeneratorBuilder().build()
        val schema = generator.generateSchema(HeaderRouteFixture::class.java).asJsonSchema()
        schema.getProperties().assert().isNull()
    }

    @Test
    fun `should not ignore path variable when option is disabled`() {
        val generator = SchemaGeneratorBuilder().wowModule(WowModule(setOf())).build()
        val schema = generator.generateSchema(CommandRouteFixture::class.java).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should not ignore header variable when option is disabled`() {
        val generator = SchemaGeneratorBuilder().wowModule(WowModule(setOf())).build()
        val schema = generator.generateSchema(HeaderRouteFixture::class.java).asJsonSchema()
        schema.getProperties().assert().isNotNull()
    }

    @Test
    fun `should produce different schemas with and without wow module`() {
        val withWow = SchemaGeneratorBuilder().build()
        val withoutWow = SchemaGeneratorBuilder().wowModule(null).build()
        val schemaWith = withWow.generateSchema(CommandRouteFixture::class.java).toPrettyString()
        val schemaWithout = withoutWow.generateSchema(CommandRouteFixture::class.java).toPrettyString()
        schemaWith.assert().isNotEqualTo(schemaWithout)
    }
}
