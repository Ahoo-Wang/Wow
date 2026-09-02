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
import me.ahoo.wow.api.query.schema.QueryCardinality
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.configuration.WowResourceLocator
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.CARDINALITY
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DYNAMIC_CHILDREN
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.FIELDS
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.NULLABLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.REQUIRED
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC_TYPE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TITLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.VALUE_TYPES
import me.ahoo.wow.serialization.JsonSerializer
import reactor.core.publisher.Flux
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
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
        resources.findWorkingDirectory(QUERY_SCHEMA_FEATURE, context.resourceKey())?.let { resource ->
            return@defer Flux.just(readConventionDeclaration(resource.location, resource::readText))
        }
        val legacy = basePath.resolve(context.legacyResourcePath())
        if (Files.notExists(legacy)) {
            Flux.empty()
        } else {
            Flux.just(readConventionDeclaration(legacy.toString()) { readText(legacy) })
        }
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
        if (unified.isNotEmpty()) {
            return unified.map { resource ->
                readConventionDeclaration(resource.location, resource::readText)
            }
        }
        val legacyPath = context.legacyResourcePath()
        val legacy = try {
            Collections.list(classLoader.getResources(legacyPath)).sortedBy(URL::toExternalForm)
        } catch (error: Exception) {
            throw QuerySchemaUnavailableException("Unable to list query schema resources [$legacyPath].", error)
        }
        return legacy.map { resource ->
            readConventionDeclaration(resource.toExternalForm()) {
                resource.openStream().bufferedReader().use { it.readText() }
            }
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

private fun QuerySchemaContext.legacyResourcePath(): String =
    "wow-query-schema/${namedAggregate.contextName}/${namedAggregate.aggregateName}/" +
        "${model.value.lowercase(Locale.ROOT)}.json"

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
    require(fields is ObjectNode) {
        "Query schema [$FIELDS] must be an object."
    }
    return QuerySchemaDeclaration(
        fields.properties().associate { (field, declaration) ->
            require(declaration is ObjectNode) { "Query schema field [$field] must be an object." }
            QueryField(field) to declaration.toDeclaration(field)
        },
    )
}

private fun ObjectNode.toDeclaration(field: String): QueryFieldDeclaration {
    requireOnly(FIELD_PROPERTIES)
    return QueryFieldDeclaration(
        title = nullableText(TITLE, field),
        description = nullableText(DESCRIPTION, field),
        enumValues = nullableEnumValues(field),
        valueTypes = valueTypes(field),
        nullable = boolean(NULLABLE, field),
        required = boolean(REQUIRED, field),
        cardinality = cardinality(field),
        semanticType = semanticType(field),
        dynamicChildren = boolean(DYNAMIC_CHILDREN, field),
    )
}

private fun ObjectNode.nullableText(name: String, field: String): DeclarationValue<String?> {
    if (!has(name)) return DeclarationValue.Unset
    val value = get(name)
    if (value.isNull) return DeclarationValue.Set(null)
    require(value.isString) { "Query schema [$field.$name] must be a string or null." }
    return DeclarationValue.Set(value.stringValue())
}

private fun ObjectNode.nullableEnumValues(field: String): DeclarationValue<List<JsonNode>?> {
    if (!has(ENUM_VALUES)) return DeclarationValue.Unset
    val value = get(ENUM_VALUES)
    if (value.isNull) return DeclarationValue.Set(null)
    require(value.isArray) {
        "Query schema [$field.$ENUM_VALUES] must be an array or null."
    }
    return DeclarationValue.Set(value.toList())
}

private fun ObjectNode.valueTypes(field: String): DeclarationValue<Set<QueryValueType>> {
    if (!has(VALUE_TYPES)) return DeclarationValue.Unset
    val value = get(VALUE_TYPES)
    require(value.isArray) {
        "Query schema [$field.$VALUE_TYPES] must be an array."
    }
    return DeclarationValue.Set(
        value.asSequence().map { item ->
            require(item.isString) {
                "Query schema [$field.$VALUE_TYPES] values must be strings."
            }
            QueryValueType.from(item.stringValue())
        }.toSet(),
    )
}

private fun ObjectNode.boolean(name: String, field: String): DeclarationValue<Boolean> {
    if (!has(name)) return DeclarationValue.Unset
    val value = get(name)
    require(value.isBoolean) { "Query schema [$field.$name] must be a boolean." }
    return DeclarationValue.Set(value.booleanValue())
}

private fun ObjectNode.cardinality(field: String): DeclarationValue<QueryCardinality> {
    if (!has(CARDINALITY)) return DeclarationValue.Unset
    val value = get(CARDINALITY)
    require(value.isString) {
        "Query schema [$field.$CARDINALITY] must be a string."
    }
    return DeclarationValue.Set(QueryCardinality.valueOf(value.stringValue()))
}

private fun ObjectNode.semanticType(field: String): DeclarationValue<QuerySemanticType?> {
    if (!has(SEMANTIC_TYPE)) return DeclarationValue.Unset
    val value = get(SEMANTIC_TYPE)
    if (value.isNull) return DeclarationValue.Set(null)
    require(value is ObjectNode) {
        "Query schema [$field.$SEMANTIC_TYPE] must be an object or null."
    }
    return DeclarationValue.Set(JsonSerializer.treeToValue(value, QuerySemanticType::class.java))
}

private fun ObjectNode.requireOnly(allowed: Set<String>) {
    val unknown = properties().map { it.key }.filterNot(allowed::contains)
    require(unknown.isEmpty()) { "Unknown query schema properties: $unknown." }
}

private val ROOT_PROPERTIES = setOf(FIELDS)

private val FIELD_PROPERTIES = setOf(
    TITLE,
    DESCRIPTION,
    ENUM_VALUES,
    VALUE_TYPES,
    NULLABLE,
    REQUIRED,
    CARDINALITY,
    SEMANTIC_TYPE,
    DYNAMIC_CHILDREN,
)
