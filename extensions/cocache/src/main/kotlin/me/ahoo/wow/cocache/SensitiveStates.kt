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

package me.ahoo.wow.cocache

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.Manifest

/**
 * Whether an aggregate's states hold `@Sensitive` values: its state schema in the Manifest carries `x-wow-sensitive`,
 * on a property or on a schema it references, at any depth. A sink that writes such states into a cache must mask
 * them ([me.ahoo.wow.spi.read.StateMasks]).
 */
internal object SensitiveStates {
    /** The keyword of a `@Sensitive` value's schema. */
    const val SENSITIVE: String = "x-wow-sensitive"

    private const val REF = "\$ref"

    /** Whether the state schema of [type] in [manifest] carries [SENSITIVE]; false for a type it does not declare. */
    fun declared(manifest: Manifest, type: AggregateType): Boolean {
        val aggregate = manifest.contexts.firstOrNull { it.name == type.context }
            ?.aggregates?.firstOrNull { it.name == type.name } ?: return false
        return carries(mapOf(REF to aggregate.state.schema), manifest.defs, mutableSetOf())
    }

    private fun carries(schema: Any?, defs: Map<String, Any?>, seen: MutableSet<String>): Boolean = when (schema) {
        is Map<*, *> -> SENSITIVE in schema.keys || schema.any { (keyword, value) ->
            if (keyword == REF && value is String) referenced(value, defs, seen) else carries(value, defs, seen)
        }
        is List<*> -> schema.any { carries(it, defs, seen) }
        else -> false
    }

    /** Whether the schema [ref] names in [defs] carries [SENSITIVE], each schema walked once. */
    private fun referenced(ref: String, defs: Map<String, Any?>, seen: MutableSet<String>): Boolean {
        if (!Manifest.isSchemaRef(ref)) return false
        val key = Manifest.schemaKey(ref)
        return seen.add(key) && carries(defs[key], defs, seen)
    }
}
