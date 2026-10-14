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

/** The pieces of the document's JSON tree: objects in insertion order, and references into its components. */
internal object Json {
    const val SCHEMAS: String = "schemas"
    const val PARAMETERS: String = "parameters"
    const val HEADERS: String = "headers"
    const val REF: String = "\$ref"
    private const val COMPONENTS = "#/components/"

    /** A JSON object of the non-null [entries], in their order. */
    fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> =
        entries.filter { it.second != null }.toMap(LinkedHashMap())

    /** A reference to the component [name] of the [kind] (`schemas`, `parameters`, `headers`). */
    fun ref(kind: String, name: String): Map<String, Any?> = mapOf(REF to "$COMPONENTS$kind/$name")

    fun schemaRef(name: String): Map<String, Any?> = ref(SCHEMAS, name)

    /** The schema of a JSON type, nullable when [nullable]: `{"type": ["string", "null"]}`. */
    fun type(type: String, nullable: Boolean = false, description: String? = null): Map<String, Any?> =
        obj("type" to if (nullable) listOf(type, "null") else type, "description" to description)

    /** [schema], or null. */
    fun nullable(schema: Map<String, Any?>): Map<String, Any?> = obj("anyOf" to listOf(schema, type("null")))

    fun array(items: Map<String, Any?>): Map<String, Any?> = obj("type" to "array", "items" to items)

    /** An object of [properties], the [required] ones required. */
    fun objectSchema(
        properties: Map<String, Any?>,
        required: List<String> = emptyList(),
        description: String? = null,
    ): Map<String, Any?> = obj(
        "type" to "object",
        "description" to description,
        "properties" to properties,
        "required" to required.takeIf { it.isNotEmpty() },
    )

    /** An object whose every value is [values]: a map. */
    fun map(values: Map<String, Any?>): Map<String, Any?> = obj("type" to "object", "additionalProperties" to values)
}
