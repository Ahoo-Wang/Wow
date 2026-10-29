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
import me.ahoo.wow.api.query.QueryValueKind
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.api.wire.WireNames
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.query.backend.list
import me.ahoo.wow.query.backend.single
import me.ahoo.wow.query.resolve.jsonValue
import java.util.concurrent.TimeUnit

/**
 * The `x-wow-*` keywords of a Manifest property schema (#4332, `QueryKeywords` of `manifest-tools`), each the 1:1
 * image of a `:query-api` annotation and of the 9.x schema declaration it stands for:
 *
 * | Keyword | Declaration |
 * | --- | --- |
 * | `x-wow-sensitive` `{level}` | [MaskRule.level] |
 * | `x-wow-mask` `{keepPrefix, keepSuffix}` / `{strategy}` | [MaskRule.mask] ([QueryMask.Keep] / [QueryMask.Strategy]) |
 * | `x-wow-alias` `[path, ...]` | [QueryFieldDeclaration.aliases] |
 * | `x-wow-temporal` `{unit}` / `{pattern}` | [Temporal.Epoch] / [Temporal.Formatted] |
 * | `x-wow-decimal` `{scale}` | [NumericFormat.Decimal] |
 * | `x-wow-money` `{currency \| currencyField, scale?}` | [NumericFormat.Money.of] |
 * | `x-wow-duration` `{unit}` | [TimeSpan] |
 * | `x-wow-reference` `{aggregateName, contextName?}` | [Reference] (the declaring context when omitted) |
 * | `x-wow-index` (state root) | informational: the storage's index DDL reads it, the query schema does not |
 */
internal object ManifestQueryKeywords {
    const val SENSITIVE: String = "x-wow-sensitive"
    const val MASK: String = "x-wow-mask"
    const val ALIAS: String = "x-wow-alias"
    const val TEMPORAL: String = "x-wow-temporal"
    const val DECIMAL: String = "x-wow-decimal"
    const val MONEY: String = "x-wow-money"
    const val DURATION: String = "x-wow-duration"
    const val REFERENCE: String = "x-wow-reference"
    const val INDEX: String = "x-wow-index"
}

private const val REF = "\$ref"

/**
 * Reads a JSON Schema 2020-12 of the Manifest's `$defs`, as `manifest-tools` writes them with Jackson's own
 * serializers, into a [QueryFieldDeclaration]: local `$ref`s (a recursive one is left opaque), `anyOf`
 * and `oneOf` as unions (a `null` branch makes the value nullable), `allOf` merged, `type` (a list with `null` is
 * nullable), `properties` and `required`, `items`, `additionalProperties`, `enum`, `title`, `description`,
 * `deprecated`, the `date`/`date-time` formats as [Temporal.Date], and the `x-wow-*` keywords
 * ([ManifestQueryKeywords]). A `writeOnly` property is never in a response, so it is left out; a property whose name
 * is not a query path segment is left out too, unless it is sensitive, which fails rather than drop the protection.
 *
 * @param defs the Manifest's `$defs`, which `$ref`s point into.
 * @param contextName the bounded context of the model, the one an `x-wow-reference` without `contextName` names.
 * @param strategies resolves the custom mask strategies `x-wow-mask` names.
 */
internal class ManifestSchemaReader(
    private val defs: Map<String, Any?>,
    private val contextName: String,
    private val strategies: MaskStrategyResolver,
) {
    fun declaration(schema: Map<*, *>, field: QueryField): QueryFieldDeclaration =
        read(schema, field.path, emptySet())

    private fun read(schema: Map<*, *>, field: String, resolving: Set<String>): QueryFieldDeclaration {
        val parts = buildList {
            structure(schema, field, resolving)?.let(::add)
            schema.ref()?.let { add(reference(it, field, resolving)) }
            schema.list("allOf").forEach { add(read(it.asSchema(field), field, resolving)) }
            listOf("anyOf", "oneOf").forEach { composition ->
                schema.list(composition).takeIf { it.isNotEmpty() }?.let { branches ->
                    add(union(branches.map { read(it.asSchema(field), field, resolving) }, field))
                }
            }
        }
        val combined = parts.reduceOrNull { left, right -> left.merge(right, QueryField(field), true) }
            ?: QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.UNKNOWN))
        return combined.withMetadata(schema).withKeywords(schema, field)
    }

    private fun reference(ref: String, field: String, resolving: Set<String>): QueryFieldDeclaration {
        if (!Manifest.isSchemaRef(ref)) {
            throw QuerySchemaConflictException("Manifest schema reference must point into \$defs: [$ref] at [$field].")
        }
        val key = Manifest.schemaKey(ref)
        val target = defs[key] as? Map<*, *>
            ?: throw QuerySchemaConflictException("Manifest schema [$key] is missing from \$defs, at [$field].")
        if (key in resolving) {
            // A recursive type is not expanded again; it cannot hide a sensitive value below the recursion.
            return QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.UNKNOWN))
        }
        return read(target, field, resolving + key)
    }

    /** What `type` (or, without it, `properties` / `items`) says, or `null` when the schema states no structure. */
    private fun structure(schema: Map<*, *>, field: String, resolving: Set<String>): QueryFieldDeclaration? {
        val types = types(schema, field) ?: return untyped(schema, field, resolving)
        val nullable = "null" in types
        val shapes = types.filter { it != "null" }.distinct()
        shapes.firstOrNull { it !in SCALAR_TYPES && it != "object" && it != "array" }?.let { invalid(field, "type") }
        val scalars = shapes.mapNotNull(SCALAR_TYPES::get).toCollection(linkedSetOf())
        val branches = buildList {
            if (scalars.isNotEmpty()) add(scalar(schema, scalars, nullable))
            if ("object" in shapes) add(obj(schema, field, resolving, nullable))
            if ("array" in shapes) add(array(schema, field, resolving, nullable))
        }
        return when (branches.size) {
            0 -> NULL_BRANCH
            1 -> branches.single()
            else -> union(
                branches.map { it.copy(nullable = DeclarationValue.Set(false)) } +
                    listOfNotNull(NULL_BRANCH.takeIf { nullable }),
                field,
            )
        }
    }

    /** The structure a schema without `type` implies by its `items` or `properties`, or `null` for none. */
    private fun untyped(schema: Map<*, *>, field: String, resolving: Set<String>): QueryFieldDeclaration? = when {
        "items" in schema -> array(schema, field, resolving, nullable = null)
        "properties" in schema || "additionalProperties" in schema -> obj(schema, field, resolving, null)
        else -> null
    }

    /** The JSON types `type` names, or `null` when the schema has no `type`. */
    private fun types(schema: Map<*, *>, field: String): List<String>? = when (val type = schema["type"]) {
        null -> null
        is String -> listOf(type)
        is List<*> -> type.map { it as? String ?: invalid(field, "type") }
        else -> invalid(field, "type")
    }

    private fun scalar(schema: Map<*, *>, types: Set<QueryValueType>, nullable: Boolean): QueryFieldDeclaration {
        val format = schema["format"] as? String
        return QueryFieldDeclaration(
            kind = DeclarationValue.Set(QueryValueKind.SCALAR),
            valueTypes = DeclarationValue.Set(types),
            nullable = DeclarationValue.Set(nullable),
            semanticType = if (types == setOf(QueryValueType.STRING) && format in DATE_FORMATS) {
                DeclarationValue.Set(Temporal.Date)
            } else {
                DeclarationValue.Unset
            },
        )
    }

    private fun obj(schema: Map<*, *>, field: String, resolving: Set<String>, nullable: Boolean?): QueryFieldDeclaration {
        val required = schema.list("required").mapNotNull { it as? String }.toSet()
        val properties = linkedMapOf<String, QueryFieldDeclaration>()
        (schema["properties"] as? Map<*, *>)?.forEach { (name, child) ->
            name as String
            val property = child.asSchema("$field.$name")
            if (property["writeOnly"] == true) return@forEach
            if (!name.isValidQueryPathSegment()) {
                if (property.hasSensitiveValues(emptySet())) {
                    throw QuerySchemaConflictException(
                        "Masked query schema property is not a valid QueryField: [$field[\"$name\"]].",
                    )
                }
                return@forEach
            }
            properties[name] = read(property, "$field.$name", resolving)
                .copy(required = DeclarationValue.Set(name in required))
        }
        val additional = when (val values = schema["additionalProperties"]) {
            is Map<*, *> -> read(values, "$field.{key}", resolving)
            true -> QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.UNKNOWN))
            else -> null
        }
        return QueryFieldDeclaration(
            kind = DeclarationValue.Set(QueryValueKind.OBJECT),
            valueTypes = DeclarationValue.Set(setOf(QueryValueType.OBJECT)),
            nullable = nullable?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
            properties = DeclarationValue.Set(properties),
            additionalProperties = additional?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
        )
    }

    private fun array(schema: Map<*, *>, field: String, resolving: Set<String>, nullable: Boolean?): QueryFieldDeclaration {
        val items = (schema["items"] as? Map<*, *>)?.let { read(it, field, resolving) }
            ?: QueryFieldDeclaration(kind = DeclarationValue.Set(QueryValueKind.UNKNOWN))
        return QueryFieldDeclaration(
            kind = DeclarationValue.Set(QueryValueKind.ARRAY),
            nullable = nullable?.let { DeclarationValue.Set(it) } ?: DeclarationValue.Unset,
            items = DeclarationValue.Set(items),
        )
    }

    /** One value of [branches]: a single non-null branch made nullable by a `null` one, else a union. */
    private fun union(branches: List<QueryFieldDeclaration>, field: String): QueryFieldDeclaration {
        val nullable = branches.any { it.inferredKind() == QueryValueKind.NULL }
        val values = branches.filter { it.inferredKind() != QueryValueKind.NULL }
        return when (values.size) {
            0 -> NULL_BRANCH
            1 -> values.single().copy(
                nullable = DeclarationValue.Set(nullable || values.single().nullable.valueOr(false))
            )
            else -> {
                val alternatives = values.map { it.copy(nullable = DeclarationValue.Set(false)) }
                requireConsistentMaskRules(alternatives, field)
                QueryFieldDeclaration(
                    kind = DeclarationValue.Set(QueryValueKind.UNION),
                    alternatives = DeclarationValue.Set(alternatives + listOfNotNull(NULL_BRANCH.takeIf { nullable })),
                )
            }
        }
    }

    private fun QueryFieldDeclaration.withMetadata(schema: Map<*, *>): QueryFieldDeclaration {
        var result = this
        (schema["title"] as? String)?.let { result = result.copy(title = DeclarationValue.Set(it)) }
        (schema["description"] as? String)?.let { result = result.copy(description = DeclarationValue.Set(it)) }
        (schema["enum"] as? List<*>)?.let { values ->
            result = result.copy(enumValues = DeclarationValue.Set(values.map { it.jsonValue() }))
        }
        if (schema["deprecated"] == true) result = result.copy(deprecated = DeclarationValue.Set(QueryDeprecation()))
        return result
    }

    @Suppress("CyclomaticComplexMethod")
    private fun QueryFieldDeclaration.withKeywords(schema: Map<*, *>, field: String): QueryFieldDeclaration {
        val keywords = ManifestKeywords(schema, field)
        val semantics = listOfNotNull(
            keywords.temporal(),
            keywords.decimal(),
            keywords.money(),
            keywords.duration(),
            keywords.reference(contextName),
        )
        if (semantics.size > 1) {
            throw QuerySchemaConflictException("A field has one semantic type: [$field] declares several.")
        }
        var result = when (val semantic = semantics.singleOrNull()) {
            null -> this
            is Temporal -> withTemporal(semantic, field)
            else -> withScalarSemantic(semantic, field)
        }
        keywords.aliases()?.let { result = result.copy(aliases = DeclarationValue.Set(it)) }
        keywords.maskRule(strategies)?.let { result = result.withMaskRule(it, field) }
        return result
    }

    /** Whether this schema, or any it reaches, declares `x-wow-sensitive`. */
    private fun Map<*, *>.hasSensitiveValues(resolving: Set<String>): Boolean {
        if (ManifestQueryKeywords.SENSITIVE in this) return true
        val referenced = ref()?.takeIf { Manifest.isSchemaRef(it) }?.let(Manifest::schemaKey)
        if (referenced != null && referenced !in resolving &&
            (defs[referenced] as? Map<*, *>)?.hasSensitiveValues(resolving + referenced) == true
        ) {
            return true
        }
        return values.any { value ->
            when (value) {
                is Map<*, *> -> value.hasSensitiveValues(resolving)
                is List<*> -> value.any { (it as? Map<*, *>)?.hasSensitiveValues(resolving) == true }
                else -> false
            }
        }
    }

    private fun Map<*, *>.ref(): String? = this[REF] as? String

    private fun Map<*, *>.list(key: String): List<*> = this[key] as? List<*> ?: emptyList<Any?>()

    private fun Any?.asSchema(field: String): Map<*, *> = this as? Map<*, *> ?: invalid(field, "schema")

    private companion object {
        val SCALAR_TYPES = mapOf(
            "string" to QueryValueType.STRING,
            "integer" to QueryValueType.INTEGER,
            "number" to QueryValueType.DECIMAL,
            "boolean" to QueryValueType.BOOLEAN,
        )
        val DATE_FORMATS = setOf("date", "date-time")
        val NULL_BRANCH = QueryFieldDeclaration(
            kind = DeclarationValue.Set(QueryValueKind.NULL),
            nullable = DeclarationValue.Set(true),
        )
    }
}

/** The `x-wow-*` keywords of one property schema, read with messages that name the field. */
private class ManifestKeywords(private val schema: Map<*, *>, private val field: String) {
    fun temporal(): Temporal? = keyword(ManifestQueryKeywords.TEMPORAL)?.let { temporal ->
        val unit = temporal["unit"] as? String
        val pattern = temporal["pattern"] as? String
        declared(ManifestQueryKeywords.TEMPORAL) {
            require((unit == null) != (pattern == null)) { "declares either a unit or a pattern, not both" }
            if (pattern != null) Temporal.Formatted(pattern) else Temporal.Epoch(TimeUnit.valueOf(unit!!))
        }
    }

    fun decimal(): NumericFormat? = keyword(ManifestQueryKeywords.DECIMAL)?.let { decimal ->
        declared(ManifestQueryKeywords.DECIMAL) { NumericFormat.Decimal(decimal.int("scale")) }
    }

    fun money(): NumericFormat? = keyword(ManifestQueryKeywords.MONEY)?.let { money ->
        declared(ManifestQueryKeywords.MONEY) {
            NumericFormat.Money.of(
                currency = money["currency"] as? String,
                currencyField = money["currencyField"] as? String,
                scale = (money["scale"] as? Number)?.toInt(),
            )
        }
    }

    fun duration(): TimeSpan? = keyword(ManifestQueryKeywords.DURATION)?.let { duration ->
        declared(ManifestQueryKeywords.DURATION) { TimeSpan(TimeUnit.valueOf(duration["unit"] as String)) }
    }

    fun reference(contextName: String): Reference? = keyword(ManifestQueryKeywords.REFERENCE)?.let { reference ->
        declared(ManifestQueryKeywords.REFERENCE) {
            Reference(
                contextName = reference["contextName"] as? String ?: contextName,
                aggregateName = reference["aggregateName"] as String,
            )
        }
    }

    fun aliases(): Set<QueryField>? = schema[ManifestQueryKeywords.ALIAS]?.let { aliases ->
        declared(ManifestQueryKeywords.ALIAS) {
            (aliases as List<*>).mapTo(linkedSetOf()) { QueryField(it as String) }
        }
    }

    fun maskRule(strategies: MaskStrategyResolver): MaskRule? {
        val sensitive = keyword(ManifestQueryKeywords.SENSITIVE)
        val mask = keyword(ManifestQueryKeywords.MASK)
        if (sensitive == null) {
            if (mask != null) conflict("${ManifestQueryKeywords.MASK} requires ${ManifestQueryKeywords.SENSITIVE}")
            return null
        }
        val level = declared(ManifestQueryKeywords.SENSITIVE) {
            val name = sensitive["level"] as String
            requireNotNull(WireNames.entryOf<SensitivityLevel>(name)) {
                "Not a sensitivity level: '$name'; one of ${WireNames.namesOf<SensitivityLevel>().joinToString()}."
            }
        }
        val queryMask = mask?.let {
            declared(ManifestQueryKeywords.MASK) {
                when (val strategy = it["strategy"] as? String) {
                    null -> QueryMask.Keep(it.int("keepPrefix", 0), it.int("keepSuffix", 0))
                    else -> {
                        require("keepPrefix" !in it && "keepSuffix" !in it) {
                            "a custom strategy keeps no prefix or suffix"
                        }
                        QueryMask.Strategy(strategy)
                    }
                }
            }
        } ?: QueryMask.FULL
        return MaskRule(level, queryMask, strategies)
    }

    private fun keyword(name: String): Map<*, *>? = schema[name]?.let {
        it as? Map<*, *> ?: conflict("$name must be an object")
    }

    private fun Map<*, *>.int(key: String, default: Int? = null): Int =
        (this[key] as? Number)?.toInt() ?: default ?: throw IllegalArgumentException("$key is required")

    /** The value [read] builds, a malformed keyword failing the schema build with its own message. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> declared(keyword: String, read: () -> T): T = try {
        read()
    } catch (error: RuntimeException) {
        throw QuerySchemaConflictException("Invalid $keyword on [$field]: ${error.message}", error)
    }

    private fun conflict(message: String): Nothing =
        throw QuerySchemaConflictException("Invalid Manifest query keyword on [$field]: $message.")
}

private fun invalid(field: String, keyword: String): Nothing =
    throw QuerySchemaConflictException("Invalid Manifest schema [$keyword] at [$field].")

/**
 * The values of a map, an object of dynamic keys with no named property, or `null` when this declaration is no map:
 * an annotation on a map property is about its values, as on a collection it is about its items.
 */
private fun QueryFieldDeclaration.mapValues(): QueryFieldDeclaration? =
    additionalProperties.valueOr(null)?.takeIf {
        inferredKind() == QueryValueKind.OBJECT && properties.valueOr(emptyMap()).isEmpty()
    }

/**
 * Applies a numeric format, a duration or a reference to every non-null leaf (an array's items, a map's values, a
 * union's alternatives), which must be a scalar; the schema checks the leaf's type and any sibling field the semantic
 * type names.
 */
private fun QueryFieldDeclaration.withScalarSemantic(
    semantic: QuerySemanticType,
    field: String,
): QueryFieldDeclaration = when (inferredKind()) {
    QueryValueKind.ARRAY -> copy(
        items = DeclarationValue.Set(items.valueOr(null)!!.withScalarSemantic(semantic, field)),
    )
    QueryValueKind.UNION -> copy(
        alternatives = DeclarationValue.Set(
            alternatives.valueOr(emptyList()).map { branch ->
                if (branch.inferredKind() == QueryValueKind.NULL) branch else branch.withScalarSemantic(semantic, field)
            },
        ),
    )
    QueryValueKind.SCALAR -> copy(semanticType = DeclarationValue.Set(semantic))
    else -> mapValues()?.let { values ->
        copy(additionalProperties = DeclarationValue.Set(values.withScalarSemantic(semantic, "$field.{key}")))
    } ?: throw QuerySchemaConflictException("A semantic type requires a scalar field, but [$field] is not.")
}

/**
 * Applies a declared time encoding to every non-null leaf (an array's items, a map's values, a union's alternatives),
 * which must have its JSON wire type.
 */
private fun QueryFieldDeclaration.withTemporal(temporal: Temporal, field: String): QueryFieldDeclaration {
    val wire = if (temporal is Temporal.Formatted) QueryValueType.STRING else QueryValueType.INTEGER
    return when (inferredKind()) {
        QueryValueKind.ARRAY -> copy(
            items = DeclarationValue.Set(items.valueOr(null)!!.withTemporal(temporal, field)),
        )
        QueryValueKind.UNION -> copy(
            alternatives = DeclarationValue.Set(
                alternatives.valueOr(emptyList()).map { branch ->
                    if (branch.inferredKind() == QueryValueKind.NULL) branch else branch.withTemporal(temporal, field)
                },
            ),
        )
        QueryValueKind.SCALAR -> {
            if (valueTypes.valueOr(emptySet()) != setOf(wire)) {
                throw QuerySchemaConflictException(
                    "${ManifestQueryKeywords.TEMPORAL} on [$field] requires the ${wire.value.lowercase()} JSON type.",
                )
            }
            copy(semanticType = DeclarationValue.Set(temporal))
        }
        else -> mapValues()?.let { values ->
            copy(additionalProperties = DeclarationValue.Set(values.withTemporal(temporal, "$field.{key}")))
        } ?: throw QuerySchemaConflictException(
            "${ManifestQueryKeywords.TEMPORAL} on [$field] requires the ${wire.value.lowercase()} JSON type.",
        )
    }
}

/**
 * Puts [rule] on the string values this declaration holds: here when it is a string domain (strings, possibly in
 * nested arrays), else on the values of a map, else on each non-null alternative. Any other shape fails closed: the
 * value would otherwise be left unprotected.
 */
private fun QueryFieldDeclaration.withMaskRule(rule: MaskRule, field: String): QueryFieldDeclaration {
    if (isMaskStringDomain()) return copy(maskRule = DeclarationValue.Set(rule))
    mapValues()?.let { values ->
        return copy(additionalProperties = DeclarationValue.Set(values.withMaskRule(rule, "$field.{key}")))
    }
    if (inferredKind() == QueryValueKind.UNION) {
        return copy(
            alternatives = DeclarationValue.Set(
                alternatives.valueOr(emptyList()).map { branch ->
                    if (branch.inferredKind() == QueryValueKind.NULL) branch else branch.withMaskRule(rule, field)
                },
            ),
        )
    }
    throw QuerySchemaConflictException("Masked query schema field must have STRING value type: [$field].")
}

/** Alternatives of one value must not mask the same path differently: a response value takes one rule. */
private fun requireConsistentMaskRules(alternatives: List<QueryFieldDeclaration>, field: String) {
    val rules = mutableMapOf<List<String>, MaskRule>()
    fun collect(value: QueryFieldDeclaration, path: List<String>) {
        value.maskRule.valueOr(null)?.let { rule ->
            if (rules.putIfAbsent(path, rule)?.let { it != rule } == true) {
                throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.maskRule].")
            }
        }
        value.properties.valueOr(emptyMap()).forEach { (name, child) -> collect(child, path + name) }
        value.items.valueOr(null)?.let { collect(it, path + "[]") }
        value.additionalProperties.valueOr(null)?.let { collect(it, path + "{}") }
        value.alternatives.valueOr(emptyList()).forEach { collect(it, path) }
    }
    alternatives.forEach { collect(it, emptyList()) }
}
