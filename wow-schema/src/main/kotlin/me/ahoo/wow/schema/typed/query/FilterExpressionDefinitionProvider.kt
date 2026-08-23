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
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.schema.WowSchemaLoader

object FilterExpressionDefinitionProvider : CustomDefinitionProviderV2 {
    override fun provideCustomSchemaDefinition(
        javaType: ResolvedType,
        context: SchemaGenerationContext,
    ): CustomDefinition? {
        return when (javaType.erasedType) {
            AggregationElementFilterExpressionSchema::class.java -> CustomDefinition(
                loadAggregationElementFilterSchema()
            )
            in SUPPORTED_TYPES -> CustomDefinition(WowSchemaLoader.load(FilterExpression::class.java))
            else -> null
        }
    }

    private fun loadAggregationElementFilterSchema() = WowSchemaLoader.load(
        FilterExpression::class.java
    ).also { schema ->
        schema.remove("\$id")
        val supportedDefinitions = setOf(
            "matchAll", "matchNone", "and", "or", "nor",
            "eq", "ne", "gt", "gte", "lt", "lte", "contains", "startsWith", "endsWith",
            "in", "notIn", "between", "isNull", "isNotNull", "exists", "notExists",
            "today", "beforeToday", "tomorrow", "thisWeek", "nextWeek", "lastWeek",
            "thisMonth", "lastMonth", "recentDays", "earlierDays",
        )
        val oneOf = schema.path("definitions").path("filterExpression").path("oneOf")
        val supported = oneOf.filter { reference ->
            reference.path("\$ref").stringValue().substringAfterLast('/') in supportedDefinitions
        }
        (oneOf as tools.jackson.databind.node.ArrayNode).removeAll()
        supported.forEach(oneOf::add)

        val definitions = schema.path("definitions") as tools.jackson.databind.node.ObjectNode
        val retainedDefinitions = mutableSetOf("filterExpression")
        while (true) {
            val referencedDefinitions = retainedDefinitions.flatMap { definition ->
                definitions.path(definition).findValuesAsString("\$ref")
                    .map { it.substringAfterLast('/') }
                    .filter(definitions::has)
            }
            if (!retainedDefinitions.addAll(referencedDefinitions)) break
        }
        val unusedDefinitions = definitions.propertyNames()
            .filterNot(retainedDefinitions::contains)
            .toList()
        definitions.remove(unusedDefinitions)
    }

    private val SUPPORTED_TYPES = setOf(FilterExpression::class.java, FilterExpressionSchema::class.java)
}

@Schema(name = "api.query.FilterExpression")
sealed interface FilterExpressionSchema {
    data object MatchAll : FilterExpressionSchema
}

@Schema(name = "api.query.AggregationElementFilterExpression")
sealed interface AggregationElementFilterExpressionSchema {
    data object MatchAll : AggregationElementFilterExpressionSchema
}
