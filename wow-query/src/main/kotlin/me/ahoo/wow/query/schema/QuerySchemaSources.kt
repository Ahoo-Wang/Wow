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

import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.FIELDS
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ITEMS
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.KIND
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.NULLABLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.PROPERTIES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TYPES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.VALUES
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BeanQuerySchemaSource(
    registrations: List<QuerySchemaRegistration>,
) : QuerySchemaSource {
    private val registrations = registrations.toList()

    override val priority: Int = QuerySchemaSourcePriority.BEAN

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> =
        Flux.fromIterable(registrations)
            .filter { it.context == context }
            .map(QuerySchemaRegistration::declaration)
}

class WorkingDirectoryQuerySchemaSource(
    private val basePath: Path = Path.of("config"),
    private val readText: (Path) -> String = Files::readString,
) : QuerySchemaSource {
    private val resources = WowResourceLocator(configDirectory = basePath, pathReader = readText)

    override val priority: Int = QuerySchemaSourcePriority.WORKING_DIRECTORY

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
        val resource = resources.findWorkingDirectory(QUERY_SCHEMA_FEATURE, context.resourceKey())
            ?: return@defer Flux.empty()
        Flux.just(readConventionDeclaration(resource.location, resource::readText))
    }.subscribeOn(Schedulers.boundedElastic())
}

class ClasspathQuerySchemaSource(
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: ClasspathQuerySchemaSource::class.java.classLoader,
) : QuerySchemaSource {
    private val resources = WowResourceLocator(classLoader = classLoader)
    private val cache = ConcurrentHashMap<QuerySchemaContext, List<QuerySchemaDeclaration>>()

    override val priority: Int = QuerySchemaSourcePriority.CLASSPATH

    override fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
        Flux.fromIterable(cache.computeIfAbsent(context, ::readDeclarations))
    }.subscribeOn(Schedulers.boundedElastic())

    override fun refresh(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = Flux.defer {
        cache.remove(context)
        load(context)
    }

    // This I/O boundary preserves every recoverable loading failure as the public cause.
    @Suppress(
        "TooGenericExceptionCaught",
    )
    private fun readDeclarations(context: QuerySchemaContext): List<QuerySchemaDeclaration> {
        val unified = try {
            resources.findClasspath(QUERY_SCHEMA_FEATURE, context.resourceKey())
        } catch (error: Exception) {
            throw QuerySchemaUnavailableException(
                "Unable to list query schema resources [${context.resourceKey()}].",
                error,
            )
        }
        return unified.map { resource ->
            readConventionDeclaration(resource.location, resource::readText)
        }
    }
}

private const val QUERY_SCHEMA_FEATURE = "query-schema"

private fun QuerySchemaContext.resourceKey(): String {
    val segments = listOf(namedAggregate.contextName, namedAggregate.aggregateName, model.value)
    segments.forEach { segment ->
        require(segment.isNotBlank() && '/' !in segment && '\\' !in segment && segment != "." && segment != "..") {
            "Query schema path segment is invalid: [$segment]."
        }
    }
    return "${segments[0]}.${segments[1]}.${segments[2].lowercase(Locale.ROOT)}"
}

// Convention parsing must retain any recoverable read or validation failure as its cause.
@Suppress(
    "TooGenericExceptionCaught",
)
private inline fun readConventionDeclaration(
    location: String,
    read: () -> String,
): QuerySchemaDeclaration = try {
    parseConventionDeclaration(read())
} catch (error: Exception) {
    throw QuerySchemaUnavailableException("Unable to read query schema [$location].", error)
}

private fun parseConventionDeclaration(json: String): QuerySchemaDeclaration {
    val root = JsonSerializer.readTree(json)
    require(root is ObjectNode) { "Query schema root must be an object." }
    root.requireOnly(ROOT_PROPERTIES)
    val fields = root.get(FIELDS)
    require(fields is ObjectNode) { "Query schema [$FIELDS] must be an object." }
    return QuerySchemaDeclaration(
        fields.properties().associate { (field, declaration) ->
            require(declaration is ObjectNode) { "Query schema field [$field] must be an object." }
            QueryField(field) to declaration.toDeclaration(field)
        },
    )
}

private fun ObjectNode.toDeclaration(field: String): QueryFieldDeclaration {
    requireOnly(FIELD_PROPERTIES)
    val enum = enum(field)
    return QueryFieldDeclaration(
        kind = kind(field),
        valueTypes = types(field),
        nullable = boolean(NULLABLE, field),
        enumValues = enum?.let { values -> DeclarationValue.Set(values.map { it.first }) } ?: DeclarationValue.Unset,
        enumDescriptions = enum?.let { values ->
            DeclarationValue.Set(values.mapNotNull { (value, description) -> description?.let { value to it } }.toMap())
        } ?: DeclarationValue.Unset,
        semanticType = semantic(field),
        description = text(DESCRIPTION, field),
        properties = properties(field),
        items = child(ITEMS, field),
        additionalProperties = child(VALUES, field),
    )
}

private fun ObjectNode.kind(field: String): DeclarationValue<QueryValueKind> {
    val value = get(KIND) ?: return DeclarationValue.Unset
    val kind = value.takeIf(JsonNode::isString)?.stringValue()
        ?.let { name -> DECLARABLE_KINDS.firstOrNull { it.name == name } }
    require(kind != null) { "Query schema [$field.$KIND] must be one of $DECLARABLE_KINDS." }
    return DeclarationValue.Set(kind)
}

private fun ObjectNode.types(field: String): DeclarationValue<Set<QueryValueType>> {
    val value = get(TYPES) ?: return DeclarationValue.Unset
    require(value.isArray && !value.isEmpty) { "Query schema [$field.$TYPES] must be a non-empty array." }
    return DeclarationValue.Set(
        value.asSequence().map { item ->
            val type = item.takeIf(JsonNode::isString)?.stringValue()?.let(QueryValueType::from)
            require(
                type in DECLARABLE_TYPES
            ) { "Query schema [$field.$TYPES] values must be one of $DECLARABLE_TYPES." }
            checkNotNull(type)
        }.toSet(),
    )
}

private fun ObjectNode.boolean(name: String, field: String): DeclarationValue<Boolean> {
    val value = get(name) ?: return DeclarationValue.Unset
    require(value.isBoolean) { "Query schema [$field.$name] must be a boolean." }
    return DeclarationValue.Set(value.booleanValue())
}

private fun ObjectNode.text(name: String, field: String): DeclarationValue<String?> {
    val value = get(name) ?: return DeclarationValue.Unset
    require(value.isString) { "Query schema [$field.$name] must be a string." }
    return DeclarationValue.Set(value.stringValue())
}

/** The declared values, each with its description; `null` when the field declares no enum. */
private fun ObjectNode.enum(field: String): List<Pair<JsonNode, String?>>? {
    val value = get(ENUM) ?: return null
    require(value.isArray && !value.isEmpty) { "Query schema [$field.$ENUM] must be a non-empty array." }
    val values = value.toList().map { entry ->
        require(entry is ObjectNode && entry.has(ENUM_VALUE)) {
            "Query schema [$field.$ENUM] entries must be objects with a [$ENUM_VALUE]."
        }
        entry.requireOnly(ENUM_PROPERTIES)
        val description = entry.get(ENUM_DESCRIPTION)
        require(description == null || description.isString) {
            "Query schema [$field.$ENUM.$ENUM_DESCRIPTION] must be a string."
        }
        entry.get(ENUM_VALUE) to description?.stringValue()
    }
    require(values.map { it.first }.distinct().size == values.size) { "Query schema [$field.$ENUM] repeats a value." }
    return values
}

private fun ObjectNode.semantic(field: String): DeclarationValue<QuerySemanticType?> {
    val value = get(SEMANTIC) ?: return DeclarationValue.Unset
    require(value is ObjectNode) { "Query schema [$field.$SEMANTIC] must be an object." }
    return DeclarationValue.Set(JsonSerializer.treeToValue(value, QuerySemanticType::class.java))
}

private fun ObjectNode.properties(field: String): DeclarationValue<Map<String, QueryFieldDeclaration>> {
    val value = get(PROPERTIES) ?: return DeclarationValue.Unset
    require(value is ObjectNode) { "Query schema [$field.$PROPERTIES] must be an object." }
    return DeclarationValue.Set(
        value.properties().associate { (name, node) ->
            requireQueryPathSegment(name)
            require(node is ObjectNode) { "Query schema [$field.$PROPERTIES.$name] must be an object." }
            name to node.toDeclaration("$field.$name")
        },
    )
}

private fun ObjectNode.child(name: String, field: String): DeclarationValue<QueryFieldDeclaration?> {
    val value = get(name) ?: return DeclarationValue.Unset
    require(value is ObjectNode) { "Query schema [$field.$name] must be an object." }
    return DeclarationValue.Set(value.toDeclaration("$field.$name"))
}

private fun ObjectNode.requireOnly(allowed: Set<String>) {
    val unknown = properties().map { it.key }.filterNot(allowed::contains)
    require(unknown.isEmpty()) { "Unknown query schema properties: $unknown." }
}

private val ROOT_PROPERTIES = setOf(FIELDS)

private val FIELD_PROPERTIES = setOf(KIND, TYPES, NULLABLE, ENUM, SEMANTIC, DESCRIPTION, PROPERTIES, ITEMS, VALUES)

private val ENUM_PROPERTIES = setOf(ENUM_VALUE, ENUM_DESCRIPTION)

/** Unions, `null` and unknown values are inferred, never declared. */
private val DECLARABLE_KINDS = listOf(QueryValueKind.SCALAR, QueryValueKind.OBJECT, QueryValueKind.ARRAY)

private val DECLARABLE_TYPES =
    setOf(QueryValueType.STRING, QueryValueType.INTEGER, QueryValueType.DECIMAL, QueryValueType.BOOLEAN)
