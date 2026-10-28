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

package me.ahoo.wow.openapi

import me.ahoo.wow.manifest.Manifest

/**
 * The Manifest's JSON Schemas (`$defs`) as the document's `components.schemas`: OpenAPI 3.1's schemas
 * are JSON Schema 2020-12, so each is the Manifest's own, with its references (`#/$defs/<key>`) pointing to the
 * component instead (`#/components/schemas/<name>`).
 *
 * A component's name is its key in `$defs`, the binary name of the class (`me.ahoo.shop.Cart$Item`), with every
 * character OpenAPI does not allow in a name (`[A-Za-z0-9._-]`) written `_`. Should two keys come out the same, or as a
 * [reserved] name (the framework's own, [WowSchemas]), the later key in order gets the first free `_<n>` suffix.
 */
internal class SchemaComponents(defs: Map<String, Any?>, reserved: Set<String>) {
    /** The component name of each key of `$defs`. */
    val names: Map<String, String> = LinkedHashMap<String, String>().also { names ->
        val taken = reserved.toMutableSet()
        defs.keys.sorted().forEach { key ->
            val base = key.replace(NOT_ALLOWED, "_")
            var name = base
            var suffix = 1
            while (name in taken) {
                name = "${base}_${++suffix}"
            }
            taken += name
            names[key] = name
        }
    }

    /** The schemas, by component name, their references rewritten. */
    val schemas: Map<String, Any?> = defs.keys.sorted().associateTo(LinkedHashMap()) { key ->
        names.getValue(key) to rewrite(defs[key])
    }

    /** The reference to the component of the Manifest's schema reference [ref] (`#/$defs/<key>`). */
    fun ref(ref: String): Map<String, Any?> = Json.schemaRef(nameOf(Manifest.schemaKey(ref)))

    /** An inline schema of the Manifest (a route's body or answer), its references pointing to their components. */
    @Suppress("UNCHECKED_CAST")
    fun inline(schema: Map<String, Any?>): Map<String, Any?> = rewrite(schema) as Map<String, Any?>

    /** The name of the component of the key [key]: an unresolved one is named as it would be. */
    private fun nameOf(key: String): String = names[key] ?: key.replace(NOT_ALLOWED, "_")

    /** [node] with every `$ref` into `$defs` pointing to its component. */
    private fun rewrite(node: Any?): Any? = when (node) {
        is Map<*, *> -> node.entries.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
            val name = key.toString()
            name to if (name == Json.REF && value is String && Manifest.isSchemaRef(value)) {
                ref(value).getValue(Json.REF)
            } else {
                rewrite(value)
            }
        }

        is List<*> -> node.map(::rewrite)
        else -> node
    }

    private companion object {
        val NOT_ALLOWED = Regex("[^A-Za-z0-9._-]")
    }
}
