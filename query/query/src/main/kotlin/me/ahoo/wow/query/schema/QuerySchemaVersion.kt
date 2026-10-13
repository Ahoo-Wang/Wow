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

package me.ahoo.wow.query.schema

import java.security.MessageDigest
import java.util.HexFormat

/**
 * The content hash of [schema] ([QueryModelSchema.version]): SHA-256 of a canonical rendering of every fact it
 * states, so two schemas that state the same facts share a version and any change to a fact changes it.
 */
internal fun queryModelSchemaVersion(schema: QueryModelSchema): String {
    val rendering = buildString {
        append("model=").append(schema.model).append('\n')
        append("profile=").append(schema.profile).append('\n')
        append("capabilities=").append(schema.capabilities).append('\n')
        append("storage=").append(schema.storage).append('\n')
        append("fullProjection=").append(schema.fullProjectionAvailable).append('\n')
        append("approximate=").append(schema.approximateMetrics).append('\n')
        append("provisional=").append(schema.provisional).append('\n')
        append("sensitivity=").append(schema.definition.sensitivity).append('\n')
        append("root=")
        render(schema.root)
        append('\n')
        schema.bindings.forEach { (path, native) ->
            append(path.segments).append(':')
            native.bindings.forEach { (capability, binding) ->
                append(capability).append('=').append(binding.physicalPath.segments)
                    .append('/').append(binding.storageTypes).append('/').append(binding.physicalScope)
                    .append('/').append(binding.indexed).append(';')
            }
            append("projection=").append(native.projectionPath?.segments)
            append(";response=").append(native.responsePath?.segments).append('\n')
        }
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(rendering.toByteArray(Charsets.UTF_8))
    return HexFormat.of().formatHex(digest)
}

private fun StringBuilder.render(value: QueryValueSchema) {
    append('{').append(value.kind).append(",n=").append(value.nullable).append(",r=").append(value.required)
    append(",t=").append(value.valueTypes).append(",s=").append(value.semanticType)
    append(",m=").append(value.maskRule)
    append(",e=").append(value.enumValues).append(",ed=").append(value.enumDescriptions)
    append(",title=").append(value.title).append(",d=").append(value.description)
    append(",v=").append(value.variant).append(",a=").append(value.aliases).append(",dep=").append(value.deprecated)
    value.properties.forEach { (name, child) ->
        append(",p:").append(name).append('=')
        render(child)
    }
    value.items?.let {
        append(",i=")
        render(it)
    }
    value.additionalProperties?.let {
        append(",ap=")
        render(it)
    }
    value.alternatives.forEach {
        append(",alt=")
        render(it)
    }
    append('}')
}
