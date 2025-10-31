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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.CustomDefinition
import com.github.victools.jsonschema.generator.CustomDefinition.AttributeInclusion
import com.github.victools.jsonschema.generator.CustomDefinition.DefinitionType
import com.github.victools.jsonschema.generator.SchemaKeyword
import com.github.victools.jsonschema.generator.SchemaVersion
import me.ahoo.wow.serialization.JsonSerializer

class JsonSchema(
    val actual: ObjectNode,
    val schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12
) {
    companion object {
        fun ObjectNode.asJsonSchema(schemaVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12): JsonSchema {
            return JsonSchema(this, schemaVersion)
        }

        fun JsonSchema.asCustomDefinition(
            definitionType: DefinitionType = DefinitionType.STANDARD,
            attributeInclusion: AttributeInclusion = AttributeInclusion.YES
        ): CustomDefinition {
            return CustomDefinition(actual, definitionType, attributeInclusion)
        }

        fun SchemaKeyword.toPropertyName(schemeVersion: SchemaVersion = SchemaVersion.DRAFT_2020_12): String {
            return this.forVersion(schemeVersion)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <R : JsonNode> get(propertyName: String): R? {
        return actual.get(propertyName) as R?
    }

    fun <R : JsonNode> requiredGet(propertyName: String): R {
        return requireNotNull(get(propertyName)) {
            "Can not find property [$propertyName] in [${actual.toPrettyString()}]."
        }
    }

    fun <R : JsonNode> get(keyword: SchemaKeyword): R? {
        return get(keyword.toPropertyName(schemaVersion))
    }

    fun <R : JsonNode> requiredGet(keyword: SchemaKeyword): R {
        return requiredGet(keyword.toPropertyName(schemaVersion))
    }

    fun getProperties(): ObjectNode? {
        return get(SchemaKeyword.TAG_PROPERTIES)
    }

    fun requiredGetProperties(): ObjectNode {
        return requiredGet(SchemaKeyword.TAG_PROPERTIES)
    }

    fun set(keyword: SchemaKeyword, value: JsonNode): JsonSchema {
        set(keyword.toPropertyName(schemaVersion), value)
        return this
    }

    fun set(propertyName: String, value: JsonNode): JsonSchema {
        actual.set<JsonNode>(propertyName, value)
        return this
    }

    fun remove(keyword: SchemaKeyword): JsonSchema {
        actual.remove(keyword.toPropertyName(schemaVersion))
        return this
    }

    fun ensureProperties(): JsonSchema {
        val propertiesName = SchemaKeyword.TAG_PROPERTIES.toPropertyName(schemaVersion)
        if (actual.has(propertiesName)) {
            return this
        }

        SchemaKeyword.TAG_TYPE.toPropertyName(schemaVersion)
        actual.set<ObjectNode>(propertiesName, JsonSerializer.createObjectNode())
        return this
    }
}
