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

package me.ahoo.wow.api.query.schema

import tools.jackson.databind.JsonNode

/** Public logical value metadata; native names and storage facts are never published. */
data class QueryModelSchemaMetadata(
    val model: QueryModel,
    val capabilities: Set<QueryCapability>,
    val root: QueryValueSchemaMetadata,
)

data class QueryValueSchemaMetadata(
    val kind: QueryValueKind,
    val title: String? = null,
    val description: String? = null,
    val enumValues: List<JsonNode>? = null,
    val valueTypes: Set<QueryValueType> = emptySet(),
    val nullable: Boolean = true,
    val required: Boolean = false,
    val semanticType: QuerySemanticType? = null,
    val properties: Map<String, QueryValueSchemaMetadata> = emptyMap(),
    val items: QueryValueSchemaMetadata? = null,
    val additionalProperties: QueryValueSchemaMetadata? = null,
    val alternatives: List<QueryValueSchemaMetadata> = emptyList(),
    val capabilities: Set<QueryCapability> = emptySet(),
    val masked: Boolean = false,
)
