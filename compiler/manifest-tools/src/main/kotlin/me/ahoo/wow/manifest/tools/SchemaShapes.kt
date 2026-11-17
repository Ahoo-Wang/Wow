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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.manifest.Manifest

/**
 * How [SchemaCompatibility] reads a JSON Schema of the Manifest: a `$ref` resolved into its side's `$defs` with the
 * keywords beside it, a nullable value as its type with `null`, and the keywords it looks at.
 */
internal object SchemaShapes {
    const val REF = "\$ref"
    const val TYPE = "type"
    const val ENUM = "enum"
    const val CONST = "const"
    const val PROPERTIES = "properties"
    const val REQUIRED = "required"
    const val ITEMS = "items"
    const val ADDITIONAL_PROPERTIES = "additionalProperties"
    const val INTEGER = "integer"
    const val NUMBER = "number"
    const val FORMAT = "format"
    const val ANY_OF = "anyOf"
    const val ALL_OF = "allOf"
    const val NULL = "null"
    val NULL_SCHEMA: Map<String, Any?> = mapOf(TYPE to NULL)

    const val ONE_OF = "oneOf"

    /** The schema that accepts nothing, `false`. */
    val FALSE: Map<String, Any?> = mapOf("not" to emptyMap<String, Any?>())

    private fun refKey(schema: Any?): String? =
        ((schema as? Map<*, *>)?.get(REF) as? String)?.takeIf { Manifest.isSchemaRef(it) }?.let(Manifest::schemaKey)

    /** Whether [schema] refers to a definition: by its `$ref`, or as the value of a nullable one ([nullableValue]). */
    fun refers(schema: Any?): Boolean =
        refKey(schema) != null || (schema as? Map<*, *>)?.let { refKey(nullableValue(it)) } != null

    /** The value a nullable [schema] accepts beside `null`, `S` of `anyOf: [{"type": "null"}, S]`; null for another. */
    private fun nullableValue(schema: Map<*, *>): Any? {
        val alternatives = schema[ANY_OF] as? List<*> ?: return null
        return alternatives.singleOrNull { it != NULL_SCHEMA }?.takeIf { alternatives.size == 2 }
    }

    /**
     * The schema as a map: `$ref` replaced by its definition with the reference's siblings ([withSiblings]), `true` by
     * `{}`, and a nullable value written as a `null` alternative beside a typed one (`anyOf: [{"type": "null"}, S]`, as a
     * nullable class is) read as S with `null` among its types, the type union a nullable scalar is written as: the two
     * forms accept the same values.
     */
    fun resolve(schema: Any?, defs: Map<String, Any?>): Map<String, Any?> {
        if (schema == false) return FALSE
        @Suppress("UNCHECKED_CAST")
        val map = schema as? Map<String, Any?> ?: return emptyMap()
        val key = refKey(map) ?: return nullable(map, defs)
        val definition = resolve(defs[key] ?: true, defs)
        return if (definition === FALSE) FALSE else withSiblings(definition, map - REF)
    }

    /**
     * A reference's [definition] with the keywords written beside the reference, its [siblings], which hold as well (a
     * recursive subtype is written as a reference to its class's schema beside its discriminator's property): the
     * properties of either, a property both declare as the `allOf` of the two, and the names either requires. Any other
     * keyword the siblings give takes the definition's place.
     */
    private fun withSiblings(definition: Map<String, Any?>, siblings: Map<String, Any?>): Map<String, Any?> {
        val merged = definition + siblings
        val own = properties(definition)
        val beside = properties(siblings)
        return merged + buildMap {
            if (PROPERTIES in definition || PROPERTIES in siblings) {
                val both = own.keys.intersect(beside.keys).associateWith { mapOf(ALL_OF to listOf(own[it], beside[it])) }
                put(PROPERTIES, own + beside + both)
            }
            if (REQUIRED in definition || REQUIRED in siblings) {
                put(REQUIRED, (required(definition) + required(siblings)).toList())
            }
        }
    }

    private fun nullable(schema: Map<String, Any?>, defs: Map<String, Any?>): Map<String, Any?> {
        val value = nullableValue(schema) ?: return schema
        val resolved = resolve(value, defs)
        val types = types(resolved) ?: return schema
        return resolved + (schema - ANY_OF) + (TYPE to (types + NULL).sorted())
    }

    fun types(schema: Map<String, Any?>): Set<String>? = when (val type = schema[TYPE]) {
        is String -> setOf(type)
        is List<*> -> type.map { it.toString() }.toSet()
        else -> null
    }

    /** Whether a value of [type] is still accepted by [types]: an `integer` is a `number`. */
    fun covers(types: Set<String>, type: String): Boolean =
        type in types || (type == INTEGER && NUMBER in types)

    @Suppress("UNCHECKED_CAST")
    fun properties(schema: Map<String, Any?>): Map<String, Any?> =
        schema[PROPERTIES] as Map<String, Any?>? ?: emptyMap()

    fun required(schema: Map<String, Any?>): Set<String> =
        (schema[REQUIRED] as List<*>?).orEmpty().map { it.toString() }.toSet()

    /** The `x-wow-alias` paths of a property schema, as written on the property (beside a `$ref` too). */
    fun aliases(schema: Any?): List<String> =
        ((schema as? Map<*, *>)?.get(QueryKeywords.ALIAS_KEYWORD) as List<*>?).orEmpty().map { it.toString() }
}
