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

import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.victools.jsonschema.generator.InstanceAttributeOverrideV2
import com.github.victools.jsonschema.generator.MemberScope
import com.github.victools.jsonschema.generator.SchemaGenerationContext
import com.github.victools.jsonschema.generator.SchemaKeyword
import com.github.victools.jsonschema.generator.SchemaVersion
import io.swagger.v3.oas.models.media.Schema
import me.ahoo.wow.schema.JsonSchema.Companion.toPropertyName

class OpenAPICompatibilityAttributeOverride<M : MemberScope<*, *>?>(private val schemaVersion: SchemaVersion) :
    InstanceAttributeOverrideV2<M> {
    override fun overrideInstanceAttributes(
        collectedMemberAttributes: ObjectNode,
        member: M,
        context: SchemaGenerationContext
    ) {
        collectedMemberAttributes.replaceAttributes()
    }

    private fun ObjectNode.replaceAttributes() {
        replaceAttribute(
            SchemaKeyword.TAG_MINIMUM_EXCLUSIVE.toPropertyName(schemaVersion),
            Schema<*>::exclusiveMinimumValue.name
        )
        replaceAttribute(
            SchemaKeyword.TAG_MAXIMUM_EXCLUSIVE.toPropertyName(schemaVersion),
            Schema<*>::exclusiveMaximumValue.name
        )
    }

    private fun ObjectNode.replaceAttribute(from: String, to: String) {
        if (has(from)) {
            val value = get(from)
            remove(from)
            set<ObjectNode>(to, value)
        }
    }
}
