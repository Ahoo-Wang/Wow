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

import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import me.ahoo.test.asserts.assert
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * The structural rules of an OpenAPI 3.1 document the tests hold a generated one to, in place of the specification's
 * own schema (which the build does not carry): its fixed fields, its references, its operations and parameters, and
 * its schemas against the JSON Schema 2020-12 meta-schema.
 */
object OpenApiChecks {
    val mapper = jacksonObjectMapper()
    private val registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
    private val metaSchema = registry.getSchema(SchemaLocation.of(ManifestOpenApi.JSON_SCHEMA_DIALECT))
    private val METHODS = setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
    private val COMPONENT_NAME = Regex("^[a-zA-Z0-9.\\-_]+$")
    private val TEMPLATE = Regex("\\{([^}]+)}")

    /** Fails with every rule [document] breaks. */
    fun check(document: Map<String, Any?>) {
        val problems = problems(document)
        problems.assert().describedAs(problems.joinToString("\n")).isEmpty()
    }

    fun problems(document: Map<String, Any?>): List<String> = buildList {
        if (document["openapi"] != ManifestOpenApi.OPENAPI_VERSION) add("openapi is ${document["openapi"]}")
        val info = document["info"] as Map<*, *>
        if (info["title"] !is String || info["version"] !is String) add("info lacks a title or a version")
        val components = document["components"] as Map<*, *>
        components.values.forEach { kind ->
            (kind as Map<*, *>).keys.filterNot { COMPONENT_NAME.matches(it.toString()) }
                .forEach { add("component name $it is not allowed") }
        }
        addAll(danglingRefs(document))
        addAll(operations(document))
        (components["schemas"] as Map<*, *>).forEach { (name, schema) ->
            metaSchema.validate(mapper.writeValueAsString(schema), InputFormat.JSON).forEach {
                add("schema $name: ${it.message}")
            }
        }
    }

    /** The errors of [instance] against the component schema [name] of [document]. */
    fun validate(document: Map<String, Any?>, name: String, instance: String): List<String> {
        val root = mapOf(
            "\$schema" to ManifestOpenApi.JSON_SCHEMA_DIALECT,
            "\$ref" to "#/components/schemas/$name",
            "components" to document["components"],
        )
        return registry.getSchema(mapper.writeValueAsString(root), InputFormat.JSON)
            .validate(instance, InputFormat.JSON).map { it.toString() }
    }

    private fun danglingRefs(document: Map<String, Any?>): List<String> = refs(document).distinct().mapNotNull { ref ->
        if (resolve(document, ref) == null) "\$ref $ref resolves to nothing" else null
    }

    /** What the local reference [ref] points to in [document]. */
    fun resolve(document: Map<String, Any?>, ref: String): Any? =
        ref.removePrefix("#/").split('/').fold(document as Any?) { node, segment -> (node as? Map<*, *>)?.get(segment) }

    private fun refs(node: Any?): List<String> = when (node) {
        is Map<*, *> -> node.flatMap { (key, value) ->
            if (key == "\$ref" && value is String) listOf(value) else refs(value)
        }
        is List<*> -> node.flatMap(::refs)
        else -> emptyList()
    }

    private fun operations(document: Map<String, Any?>): List<String> = buildList {
        val operationIds = mutableListOf<String>()
        (document["paths"] as Map<*, *>).forEach { (path, item) ->
            if (!path.toString().startsWith("/")) add("path $path does not start with /")
            (item as Map<*, *>).forEach { (method, value) ->
                val operation = value as Map<*, *>
                if (method !in METHODS) add("$path has the method $method")
                operationIds += operation["operationId"].toString()
                addAll(operation(document, "$method $path", path.toString(), operation))
            }
        }
        operationIds.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { add("operationId $it is not unique") }
    }

    private fun operation(
        document: Map<String, Any?>,
        name: String,
        path: String,
        operation: Map<*, *>,
    ): List<String> = buildList {
        val declared = (operation["parameters"] as List<*>? ?: emptyList<Any>()).map { reference ->
            resolve(document, (reference as Map<*, *>)["\$ref"].toString()) as Map<*, *>
        }
        TEMPLATE.findAll(path).map { it.groupValues[1] }.forEach { variable ->
            if (declared.none { it["in"] == "path" && it["name"] == variable }) {
                add("$name declares no path parameter $variable")
            }
        }
        val responses = operation["responses"] as Map<*, *>
        if (responses.isEmpty()) add("$name has no response")
        responses.forEach { (status, response) ->
            val resolved = (response as Map<*, *>)["\$ref"]?.let { resolve(document, it.toString()) } ?: response
            if ((resolved as Map<*, *>)["description"] !is String) add("$name $status has no description")
        }
    }
}
