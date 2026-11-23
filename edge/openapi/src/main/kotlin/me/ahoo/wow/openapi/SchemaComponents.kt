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
 *
 * A type a caller sends ([inputRoots], at any depth) that holds a read-only property, a computed one the service
 * writes and never reads, has a second component, its input, named `<name>Input` once every key has its name, the
 * first free `_<n>` suffix on a clash, in key order. The input is the type's schema with each read-only property left
 * out of `required`, its schema and `readOnly` kept, and every reference to a type that has an input pointing to that
 * input: a request may leave the property out, or hand back a value read from the service, which holds it. A request's
 * schema refers to the inputs ([input], [inlineInput]); a response, an event and a state to the types themselves.
 */
internal class SchemaComponents(
    private val defs: Map<String, Any?>,
    reserved: Set<String>,
    inputRoots: Set<String> = emptySet(),
) {
    private val taken = reserved.toMutableSet()

    /** The component name of each key of `$defs`. */
    val names: Map<String, String> = LinkedHashMap<String, String>().also { names ->
        defs.keys.sorted().forEach { key -> names[key] = free(key.replace(NOT_ALLOWED, "_")) }
    }

    /** The input component's name of each key whose input differs from it, in key order. */
    val inputs: Map<String, String> = LinkedHashMap<String, String>().also { inputs ->
        val holding = holdingReadOnly()
        reached(inputRoots).filter { it in holding }.sorted().forEach { key ->
            inputs[key] = free("${names.getValue(key)}Input")
        }
    }

    /** The schemas, by component name, their references rewritten; each input right after its type. */
    val schemas: Map<String, Any?> = LinkedHashMap<String, Any?>().also { schemas ->
        defs.keys.sorted().forEach { key ->
            schemas[names.getValue(key)] = rewrite(defs[key], input = false)
            inputs[key]?.let { schemas[it] = rewrite(defs[key], input = true) }
        }
    }

    /** The reference to the component of the Manifest's schema reference [ref] (`#/$defs/<key>`). */
    fun ref(ref: String): Map<String, Any?> = Json.schemaRef(nameOf(Manifest.schemaKey(ref)))

    /** The reference a request makes to the Manifest's schema reference [ref]: its input's, when it has one. */
    fun input(ref: String): Map<String, Any?> = Json.schemaRef(inputNameOf(Manifest.schemaKey(ref)))

    /** An inline schema of the Manifest (a route's body or answer), its references pointing to their components. */
    @Suppress("UNCHECKED_CAST")
    fun inline(schema: Map<String, Any?>): Map<String, Any?> = rewrite(schema, input = false) as Map<String, Any?>

    /** An inline schema a request carries (a route's body or parameter), as an input ([inputs]). */
    @Suppress("UNCHECKED_CAST")
    fun inlineInput(schema: Map<String, Any?>): Map<String, Any?> = rewrite(schema, input = true) as Map<String, Any?>

    /** [base], or the first of `<base>_2`, `<base>_3`, … no component has taken yet. */
    private fun free(base: String): String {
        var name = base
        var suffix = 1
        while (name in taken) {
            name = "${base}_${++suffix}"
        }
        taken += name
        return name
    }

    /** The name of the component of the key [key]: an unresolved one is named as it would be. */
    private fun nameOf(key: String): String = names[key] ?: key.replace(NOT_ALLOWED, "_")

    private fun inputNameOf(key: String): String = inputs[key] ?: nameOf(key)

    private fun componentOf(key: String, input: Boolean): String = if (input) inputNameOf(key) else nameOf(key)

    /**
     * [node] with every `$ref` into `$defs` pointing to its component; as an [input], to the input where there is one,
     * and with each read-only property out of each object's `required`.
     */
    private fun rewrite(node: Any?, input: Boolean): Any? = when (node) {
        is Map<*, *> -> node.entries.associateTo(LinkedHashMap<String, Any?>()) { (key, value) ->
            val name = key.toString()
            name to when {
                name == Json.REF && value is String && Manifest.isSchemaRef(value) ->
                    Json.schemaRef(componentOf(Manifest.schemaKey(value), input)).getValue(Json.REF)

                input && name == REQUIRED && value is List<*> -> value.filterNot { readOnly(node, it) }
                else -> rewrite(value, input)
            }
        }

        is List<*> -> node.map { rewrite(it, input) }
        else -> node
    }

    /** The keys [roots] are, or that their schemas refer to, transitively, among those of `$defs`. */
    private fun reached(roots: Set<String>): Set<String> {
        val reached = mutableSetOf<String>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val key = pending.removeFirst()
            if (key in defs && reached.add(key)) pending += references(defs[key])
        }
        return reached
    }

    /** The keys whose schema holds a read-only property, in itself or through the keys it refers to. */
    private fun holdingReadOnly(): Set<String> {
        val holding = defs.keys.filterTo(mutableSetOf()) { readOnlyWithin(defs[it]) }
        do {
            val grown = defs.keys.filter { it !in holding && references(defs[it]).any(holding::contains) }
            holding += grown
        } while (grown.isNotEmpty())
        return holding
    }

    companion object {
        private val NOT_ALLOWED = Regex("[^A-Za-z0-9._-]")
        private const val REQUIRED = "required"
        private const val PROPERTIES = "properties"
        private const val READ_ONLY = "readOnly"

        /**
         * The keys a caller of [manifest] sends: its domain commands' bodies, the lifecycle bodies it holds, its
         * service routes' requests and parameters.
         */
        fun inputRoots(manifest: Manifest): Set<String> = manifest.contexts.flatMapTo(mutableSetOf()) { context ->
            context.aggregates.flatMap { aggregate ->
                aggregate.commands
                    .map { Manifest.schemaKey(it.schema) }
                    .filter { key -> key !in WowSchemas.SYSTEM_COMMANDS || key in WowSchemas.LIFECYCLE }
            } + context.routes.flatMap { route ->
                references(route.request) + route.parameters.flatMap { references(it.schema) }
            }
        }

        /** Whether the property [name] of the object schema [node] is read-only. */
        private fun readOnly(node: Map<*, *>, name: Any?): Boolean =
            ((node[PROPERTIES] as? Map<*, *>)?.get(name) as? Map<*, *>)?.get(READ_ONLY) == true

        /** The keys a schema refers to, directly; an `enum` or a `const` refers to none. */
        private fun references(node: Any?): Set<String> = mutableSetOf<String>().also { into ->
            fun visit(value: Any?) {
                when (value) {
                    is Map<*, *> -> value.forEach { (key, member) ->
                        when {
                            key == Json.REF && member is String && Manifest.isSchemaRef(member) ->
                                into += Manifest.schemaKey(member)

                            key != ENUM && key != CONST -> visit(member)
                        }
                    }

                    is List<*> -> value.forEach(::visit)
                }
            }
            visit(node)
        }

        /** Whether a schema holds a read-only property anywhere within it, its references not followed. */
        private fun readOnlyWithin(node: Any?): Boolean = when (node) {
            is Map<*, *> ->
                (node[PROPERTIES] as? Map<*, *>)?.values?.any { (it as? Map<*, *>)?.get(READ_ONLY) == true } == true ||
                    node.any { (key, value) -> key != ENUM && key != CONST && readOnlyWithin(value) }

            is List<*> -> node.any(::readOnlyWithin)
            else -> false
        }

        private const val ENUM = "enum"
        private const val CONST = "const"
    }
}
