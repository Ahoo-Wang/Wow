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

@file:Suppress("DEPRECATION")

package me.ahoo.wow.schema.typed

import com.fasterxml.classmate.ResolvedType
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import me.ahoo.wow.modeling.annotation.aggregateMetadata
import me.ahoo.wow.query.AggregationFieldCatalog
import me.ahoo.wow.schema.AggregatedFieldPaths.commandAggregatedFieldPaths
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName

object AggregatedFieldsDefinitionProvider : CustomDefinitionProviderV2 {
    private val legacyType: Class<*> = AggregatedFields::class.java
    private val aggregationTermsFieldsType: Class<*> = SnapshotAggregationTermsFields::class.java
    private val aggregationNumericFieldsType: Class<*> = SnapshotAggregationNumericFields::class.java
    private val aggregationTemporalFieldsType: Class<*> = SnapshotAggregationTemporalFields::class.java
    private val aggregationElementsType: Class<*> = SnapshotAggregationElements::class.java
    private val aggregationTypes = setOf(
        aggregationTermsFieldsType,
        aggregationNumericFieldsType,
        aggregationTemporalFieldsType,
        aggregationElementsType,
    )
    private val supportedTypes = aggregationTypes + legacyType

    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        if (supportedTypes.none(javaType::isInstanceOf)) return null
        val schemaVersion = context.generatorConfig.schemaVersion
        val rootNode = context.generatorConfig.createObjectNode()
        rootNode.put(
            SchemaKeyword.TAG_TYPE.toPropertyName(schemaVersion),
            SchemaKeyword.TAG_TYPE_STRING.toPropertyName(schemaVersion),
        )
        val aggregateType = javaType.typeBindings.getBoundType(0).erasedType
        if (aggregateType == Any::class.java) return CustomDefinition(rootNode)

        val enumValues = javaType.aggregationPaths(aggregateType)
            ?: aggregateType.kotlin.commandAggregatedFieldPaths()
        rootNode.putPOJO(SchemaKeyword.TAG_ENUM.toPropertyName(schemaVersion), enumValues)
        return CustomDefinition(rootNode)
    }

    private fun ResolvedType.aggregationPaths(commandAggregateType: Class<*>): Set<String>? {
        if (aggregationTypes.none(::isInstanceOf)) return null
        val stateType = commandAggregateType.aggregateMetadata<Any, Any>().state.aggregateType
        val catalog = AggregationFieldCatalog.scan(stateType)
        return when {
            isInstanceOf(aggregationElementsType) -> catalog.elementPaths
            isInstanceOf(aggregationTermsFieldsType) -> catalog.termsPaths
            isInstanceOf(aggregationNumericFieldsType) -> catalog.numericPaths
            else -> catalog.temporalPaths
        }
    }
}
