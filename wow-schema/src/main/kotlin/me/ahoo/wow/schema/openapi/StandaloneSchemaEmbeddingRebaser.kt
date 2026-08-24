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

package me.ahoo.wow.schema.openapi

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

internal object StandaloneSchemaEmbeddingRebaser {
    fun rebase(schema: JsonNode, schemaName: String, definitionPath: String) {
        if (schema !is ObjectNode || !schema.has("\$id")) return
        val references = schema.findValuesAsString("\$ref")
        val canRebase = schema.findValues("\$id").size == 1 && references.isNotEmpty() &&
            references.all {
                it == "#" || it.startsWith("#/definitions/") || it.startsWith("#/$definitionPath/")
            }
        if (!canRebase) return
        schema.remove("\$id")
        val componentPath = "#/$definitionPath/${schemaName.replace("~", "~0").replace("/", "~1")}"
        schema.rebaseLocalReferences(componentPath)
    }

    private fun JsonNode.rebaseLocalReferences(componentPath: String) {
        when (this) {
            is ObjectNode -> {
                get("\$ref")?.stringValue()?.let {
                    when {
                        it == "#" -> put("\$ref", componentPath)
                        it.startsWith("#/definitions/") -> put("\$ref", componentPath + it.removePrefix("#"))
                    }
                }
                properties().forEach { (_, child) -> child.rebaseLocalReferences(componentPath) }
            }
            is ArrayNode -> forEach { it.rebaseLocalReferences(componentPath) }
        }
    }
}
