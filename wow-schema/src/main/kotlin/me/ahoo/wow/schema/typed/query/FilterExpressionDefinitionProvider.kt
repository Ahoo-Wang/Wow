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
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinition.AttributeInclusion
import com.github.victools.jsonschema.generator.CustomDefinition.DefinitionType
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.api.query.AggregationExpression
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.schema.WowSchemaLoader
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

object FilterExpressionDefinitionProvider : CustomDefinitionProviderV2, Module {
    private const val TYPE_PROPERTY = "type"
    private val semanticTypeNames = QuerySemanticType::class.java
        .getDeclaredAnnotation(JsonSubTypes::class.java)
        .value.associate { it.value.java to it.name }

    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        builder.forTypesInGeneral().withCustomDefinitionProvider(this)
        builder.forFields().withTargetTypeOverridesResolver(::skipSubtypeLookup)
        builder.forMethods().withTargetTypeOverridesResolver(::skipSubtypeLookup)
    }

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        if (javaType.erasedType == FilterExpression::class.java) {
            return CustomDefinition(
                WowSchemaLoader.load(FilterExpression::class.java),
                DefinitionType.STANDARD,
                AttributeInclusion.NO,
            )
        }
        val typeName = semanticTypeNames[javaType.erasedType] ?: return null
        val definition = context.createStandardDefinition(javaType, this)
        val propertiesName = context.getKeyword(SchemaKeyword.TAG_PROPERTIES)
        val properties = definition[propertiesName] as? ObjectNode ?: definition.putObject(propertiesName)
        properties.putObject(TYPE_PROPERTY)
            .put(context.getKeyword(SchemaKeyword.TAG_CONST), typeName)
        val requiredName = context.getKeyword(SchemaKeyword.TAG_REQUIRED)
        val required = definition[requiredName] as? ArrayNode ?: definition.putArray(requiredName)
        if (required.none { it.stringValue() == TYPE_PROPERTY }) {
            required.add(TYPE_PROPERTY)
        }
        return CustomDefinition(definition)
    }

    private fun skipSubtypeLookup(scope: MemberScope<*, *>): List<ResolvedType>? =
        when (scope.type.erasedType) {
            FilterExpression::class.java,
            AggregationExpression::class.java,
            QuerySemanticType::class.java,
            -> emptyList()

            else -> null
        }
}
