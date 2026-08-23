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

package me.ahoo.wow.schema.typed.query

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.api.query.AggregationTimeZones
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName

object AggregationTimeZoneDefinitionProvider : CustomDefinitionProviderV2 {
    internal const val OFFSET_PATTERN = "^[+-](?:(?:0\\d|1[0-7]):[0-5]\\d|18:00)\$"

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        if (javaType.erasedType != AggregationTimeZoneSchema::class.java) return null
        val schemaVersion = context.generatorConfig.schemaVersion
        val typeName = SchemaKeyword.TAG_TYPE.toPropertyName(schemaVersion)
        val stringType = SchemaKeyword.TAG_TYPE_STRING.toPropertyName(schemaVersion)
        val alternatives = context.generatorConfig.createArrayNode()
        val zoneIds = context.generatorConfig.createArrayNode()
        AggregationTimeZones.ids.forEach(zoneIds::add)
        alternatives.add(
            context.generatorConfig.createObjectNode()
                .put(typeName, stringType)
                .set(
                    SchemaKeyword.TAG_ENUM.toPropertyName(schemaVersion),
                    zoneIds,
                ),
        )
        alternatives.add(
            context.generatorConfig.createObjectNode()
                .put(typeName, stringType)
                .put(SchemaKeyword.TAG_PATTERN.toPropertyName(schemaVersion), OFFSET_PATTERN),
        )
        return CustomDefinition(
            context.generatorConfig.createObjectNode()
                .set(SchemaKeyword.TAG_ONEOF.toPropertyName(schemaVersion), alternatives),
        )
    }
}
