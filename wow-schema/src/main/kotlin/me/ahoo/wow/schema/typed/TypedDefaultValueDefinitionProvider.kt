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

package me.ahoo.wow.schema.typed

import com.github.victools.jsonschema.generator.CustomDefinition.AttributeInclusion
import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.CustomPropertyDefinitionProvider
import com.github.victools.jsonschema.generator.FieldScope
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.MethodScope
import com.github.victools.jsonschema.generator.Module
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import io.swagger.v3.oas.annotations.media.Schema
import me.ahoo.wow.serialization.JsonSerializer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

internal object TypedDefaultValueDefinitionProvider : Module {
    override fun applyToConfigBuilder(builder: SchemaGeneratorConfigBuilder) {
        builder.forFields().withCustomDefinitionProvider(FieldProvider)
        builder.forMethods().withCustomDefinitionProvider(MethodProvider)
    }

    private object FieldProvider : CustomPropertyDefinitionProvider<FieldScope> {
        override fun provideCustomSchemaDefinition(
            scope: FieldScope,
            context: SchemaGenerationContext,
        ): CustomPropertyDefinition? {
            if (!scope.hasTextualDefault()) return null
            return context.createStandardDefinition(scope, this).withTypedDefault()
        }
    }

    private object MethodProvider : CustomPropertyDefinitionProvider<MethodScope> {
        override fun provideCustomSchemaDefinition(
            scope: MethodScope,
            context: SchemaGenerationContext,
        ): CustomPropertyDefinition? {
            if (!scope.hasTextualDefault()) return null
            return context.createStandardDefinition(scope, this).withTypedDefault()
        }
    }

    private fun MemberScope<*, *>.hasTextualDefault(): Boolean =
        !isFakeContainerItemScope &&
            getAnnotationConsideringFieldAndGetterIfSupported(Schema::class.java)
                ?.defaultValue
                ?.isNotEmpty() == true

    private fun JsonNode.withTypedDefault(): CustomPropertyDefinition? {
        if (this !is ObjectNode) return null
        val fragments = schemaFragments().toList()
        val defaultOwner = fragments.firstOrNull { it.path("default").isString } ?: return null
        val textualDefault = defaultOwner.path("default").stringValue()
        val types = fragments.flatMap { it.path("type").typeNames() }.toSet()
        if ("string" in types) return null
        val typedDefault = runCatching { JsonSerializer.readTree(textualDefault) }.getOrNull()
            ?.takeIf { it.matchesAny(types) }
            ?: return null
        defaultOwner.set("default", typedDefault)
        return CustomPropertyDefinition(this, AttributeInclusion.NO)
    }

    private fun ObjectNode.schemaFragments(): Sequence<ObjectNode> = sequence {
        yield(this@schemaFragments)
        listOf("allOf", "anyOf", "oneOf").forEach { keyword ->
            path(keyword).filterIsInstance<ObjectNode>().forEach { yield(it) }
        }
    }

    private fun JsonNode.typeNames(): Set<String> = when {
        isString -> setOf(stringValue())
        isArray -> mapNotNull { it.takeIf(JsonNode::isString)?.stringValue() }.toSet()
        else -> emptySet()
    }

    private fun JsonNode.matchesAny(types: Set<String>): Boolean = types.any { type ->
        when (type) {
            "integer" -> isIntegralNumber
            "number" -> isNumber
            "boolean" -> isBoolean
            "array" -> isArray
            "object" -> isObject
            "null" -> isNull
            else -> false
        }
    }
}
