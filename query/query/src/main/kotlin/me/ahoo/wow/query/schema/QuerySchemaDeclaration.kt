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

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.query.backend.single
import tools.jackson.databind.JsonNode

/** A fact a declaration states, or leaves to a source of lower priority. */
@ExperimentalWowApi
sealed interface DeclarationValue<out T> {
    data object Unset : DeclarationValue<Nothing>

    class Set<T> @InternalWowApi constructor(val value: T) : DeclarationValue<T> {
        override fun equals(other: Any?): Boolean =
            this === other || other is Set<*> && valueFields() == other.valueFields()

        override fun hashCode(): Int = valueFields().hashCode()

        override fun toString(): String = "Set(value=$value)"

        private fun valueFields(): List<Any?> = listOf(value)

        companion object {
            /** A [Set] of the given values. */
            @JvmStatic
            fun <T> of(value: T): Set<T> = Set(value)
        }
    }
}

/** The read model of one aggregate type a schema is compiled for. */
@InternalWowApi
data class QuerySchemaContext(
    val aggregateType: AggregateType,
    val model: QueryModel,
)

/** A [declaration] of [context]'s model, as [querySchemaRegistration] builds it. */
@InternalWowApi
data class QuerySchemaRegistration(
    val context: QuerySchemaContext,
    val declaration: QuerySchemaDeclaration,
)

/** What one source declares about a model's fields, by logical field. */
@ExperimentalWowApi
class QuerySchemaDeclaration @InternalWowApi constructor(
    val fields: Map<QueryField, QueryFieldDeclaration>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is QuerySchemaDeclaration && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String = "QuerySchemaDeclaration(fields=$fields)"

    private fun valueFields(): List<Any?> = listOf(fields)

    companion object {
        /** A [QuerySchemaDeclaration] of the given values. */
        @JvmStatic
        fun of(fields: Map<QueryField, QueryFieldDeclaration>): QuerySchemaDeclaration = QuerySchemaDeclaration(fields)
    }
}

/**
 * What one source declares about one logical value, in the capability descriptor's vocabulary; each fact may be left
 * [Unset][DeclarationValue.Unset] for a source of lower priority to state. A source of higher priority cannot drop
 * a sensitivity a lower one declares: replacing a protected structure keeps its masks, or fails.
 */
@ExperimentalWowApi
class QueryFieldDeclaration @InternalWowApi constructor(
    val title: DeclarationValue<String?> = DeclarationValue.Unset,
    val description: DeclarationValue<String?> = DeclarationValue.Unset,
    val enumValues: DeclarationValue<List<JsonNode>?> = DeclarationValue.Unset,
    /** What each enum value means, by value; values without a description are absent. */
    val enumDescriptions: DeclarationValue<Map<JsonNode, String>> = DeclarationValue.Unset,
    val valueTypes: DeclarationValue<Set<QueryValueType>> = DeclarationValue.Unset,
    val nullable: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val required: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val kind: DeclarationValue<QueryValueKind> = DeclarationValue.Unset,
    val properties: DeclarationValue<Map<String, QueryFieldDeclaration>> = DeclarationValue.Unset,
    val items: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
    val additionalProperties: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
    val alternatives: DeclarationValue<List<QueryFieldDeclaration>> = DeclarationValue.Unset,
    val semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
    /** The sensitivity of a string value (the Manifest's `x-wow-sensitive` and `x-wow-mask`). */
    val maskRule: DeclarationValue<MaskRule> = DeclarationValue.Unset,
    /**
     * The discriminator value of the variant this value is, when it is one alternative of a model's variant payload,
     * such as an event's type.
     */
    val variant: DeclarationValue<String?> = DeclarationValue.Unset,
    /** Other logical paths that name this value; admission replaces them with this value's path. */
    val aliases: DeclarationValue<Set<QueryField>> = DeclarationValue.Unset,
    /** Set when the field is kept only for existing callers. */
    val deprecated: DeclarationValue<QueryDeprecation?> = DeclarationValue.Unset,
) {
    /** This QueryFieldDeclaration with the given values replaced; for Wow's own query rewriting. */
    @InternalWowApi
    fun copy(
        title: DeclarationValue<String?> = this.title,
        description: DeclarationValue<String?> = this.description,
        enumValues: DeclarationValue<List<JsonNode>?> = this.enumValues,
        enumDescriptions: DeclarationValue<Map<JsonNode, String>> = this.enumDescriptions,
        valueTypes: DeclarationValue<Set<QueryValueType>> = this.valueTypes,
        nullable: DeclarationValue<Boolean> = this.nullable,
        required: DeclarationValue<Boolean> = this.required,
        kind: DeclarationValue<QueryValueKind> = this.kind,
        properties: DeclarationValue<Map<String, QueryFieldDeclaration>> = this.properties,
        items: DeclarationValue<QueryFieldDeclaration?> = this.items,
        additionalProperties: DeclarationValue<QueryFieldDeclaration?> = this.additionalProperties,
        alternatives: DeclarationValue<List<QueryFieldDeclaration>> = this.alternatives,
        semanticType: DeclarationValue<QuerySemanticType?> = this.semanticType,
        maskRule: DeclarationValue<MaskRule> = this.maskRule,
        variant: DeclarationValue<String?> = this.variant,
        aliases: DeclarationValue<Set<QueryField>> = this.aliases,
        deprecated: DeclarationValue<QueryDeprecation?> = this.deprecated,
    ): QueryFieldDeclaration = QueryFieldDeclaration(
        title,
        description,
        enumValues,
        enumDescriptions,
        valueTypes,
        nullable,
        required,
        kind,
        properties,
        items,
        additionalProperties,
        alternatives,
        semanticType,
        maskRule,
        variant,
        aliases,
        deprecated,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is QueryFieldDeclaration && valueFields() == other.valueFields()

    override fun hashCode(): Int = valueFields().hashCode()

    override fun toString(): String =
        "QueryFieldDeclaration(title=$title, description=$description, enumValues=$enumValues, " +
            "enumDescriptions=$enumDescriptions, valueTypes=$valueTypes, nullable=$nullable, required=$required, " +
            "kind=$kind, properties=$properties, items=$items, additionalProperties=$additionalProperties, " +
            "alternatives=$alternatives, semanticType=$semanticType, maskRule=$maskRule, variant=$variant, " +
            "aliases=$aliases, deprecated=$deprecated)"

    private fun valueFields(): List<Any?> = listOf(
        title,
        description,
        enumValues,
        enumDescriptions,
        valueTypes,
        nullable,
        required,
        kind,
        properties,
        items,
        additionalProperties,
        alternatives,
        semanticType,
        maskRule,
        variant,
        aliases,
        deprecated,
    )

    companion object {
        /** A [QueryFieldDeclaration] of the given values. */
        @JvmStatic
        @JvmOverloads
        fun of(
            title: DeclarationValue<String?> = DeclarationValue.Unset,
            description: DeclarationValue<String?> = DeclarationValue.Unset,
            enumValues: DeclarationValue<List<JsonNode>?> = DeclarationValue.Unset,
            enumDescriptions: DeclarationValue<Map<JsonNode, String>> = DeclarationValue.Unset,
            valueTypes: DeclarationValue<Set<QueryValueType>> = DeclarationValue.Unset,
            nullable: DeclarationValue<Boolean> = DeclarationValue.Unset,
            required: DeclarationValue<Boolean> = DeclarationValue.Unset,
            kind: DeclarationValue<QueryValueKind> = DeclarationValue.Unset,
            properties: DeclarationValue<Map<String, QueryFieldDeclaration>> = DeclarationValue.Unset,
            items: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
            additionalProperties: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
            alternatives: DeclarationValue<List<QueryFieldDeclaration>> = DeclarationValue.Unset,
            semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
            maskRule: DeclarationValue<MaskRule> = DeclarationValue.Unset,
            variant: DeclarationValue<String?> = DeclarationValue.Unset,
            aliases: DeclarationValue<Set<QueryField>> = DeclarationValue.Unset,
            deprecated: DeclarationValue<QueryDeprecation?> = DeclarationValue.Unset,
        ): QueryFieldDeclaration = QueryFieldDeclaration(
            title,
            description,
            enumValues,
            enumDescriptions,
            valueTypes,
            nullable,
            required,
            kind,
            properties,
            items,
            additionalProperties,
            alternatives,
            semanticType,
            maskRule,
            variant,
            aliases,
            deprecated,
        )
    }
}

/**
 * Where a model's field declarations come from (design §5.2): the Manifest's state schema (`$defs` plus `x-wow-*`,
 * [ManifestQuerySchemaSource]), declarations registered in code ([BeanQuerySchemaSource]), or any other source a host adds. The
 * Catalog merges the declarations of every source by [priority], a higher one stating a fact over a lower one.
 */
@InternalWowApi
interface QuerySchemaSource {
    val priority: Int

    /** The declarations of [context]'s model this source holds. */
    suspend fun load(context: QuerySchemaContext): List<QuerySchemaDeclaration>

    /** [load], re-reading what this source caches. */
    suspend fun refresh(context: QuerySchemaContext): List<QuerySchemaDeclaration> = load(context)
}

/** The priorities of the built-in sources; a host's own source picks one between or above them. */
internal object QuerySchemaSourcePriority {
    /** The Manifest's state schema ([ManifestQuerySchemaSource]), in place of 9.x's runtime inference. */
    const val MANIFEST = 100

    /** Declarations registered in code. */
    const val BEAN = 300
}

internal data class PrioritizedQuerySchemaDeclaration(
    val priority: Int,
    val declaration: QuerySchemaDeclaration,
)

internal fun QueryFieldDeclaration.merge(
    higher: QueryFieldDeclaration,
    field: QueryField,
    rejectDifferent: Boolean,
): QueryFieldDeclaration = QueryFieldDeclaration(
    title = title.merge(higher.title, field, "title", rejectDifferent),
    description = description.merge(higher.description, field, "description", rejectDifferent),
    enumValues = enumValues.merge(higher.enumValues, field, "enumValues", rejectDifferent),
    enumDescriptions = enumDescriptions.merge(higher.enumDescriptions, field, "enumDescriptions", rejectDifferent),
    valueTypes = valueTypes.merge(higher.openedValueTypes(this), field, "valueTypes", rejectDifferent),
    nullable = nullable.merge(higher.nullable, field, "nullable", rejectDifferent),
    required = required.merge(higher.required, field, "required", rejectDifferent),
    kind = kind.merge(higher.kind, field, "kind", rejectDifferent),
    properties = mergeProperties(higher, field, rejectDifferent),
    items = items.replaceStructure(higher.items, field, "items", rejectDifferent),
    additionalProperties = additionalProperties.replaceStructure(
        higher.additionalProperties,
        field,
        "additionalProperties",
        rejectDifferent
    ),
    alternatives = alternatives.merge(higher.alternatives, field, "alternatives", rejectDifferent).let { merged ->
        if (higher.alternatives is DeclarationValue.Set && alternatives is DeclarationValue.Set) {
            DeclarationValue.Set(preserveAlternativeMasks(alternatives.value, higher.alternatives.value, field))
        } else {
            merged
        }
    },
    semanticType = semanticType.merge(higher.semanticType, field, "semanticType", rejectDifferent),
    maskRule = maskRule.mergeMaskRule(higher.maskRule, field),
    variant = variant.merge(higher.variant, field, "variant", rejectDifferent),
    aliases = aliases.merge(higher.aliases, field, "aliases", rejectDifferent),
    deprecated = deprecated.merge(higher.deprecated, field, "deprecated", rejectDifferent),
)

/**
 * The value types of this declaration merged over [lower]. A declaration of kind `OBJECT` over an opaque value
 * (kind `UNKNOWN` with no value type) opens it as an object: the value types become `OBJECT`, which a declaration
 * cannot state itself ([QueryFieldDeclarationBuilder.types] is scalar only). Over any other kind the declared kind
 * alone is merged, so a scalar or array is never turned into an object.
 */
private fun QueryFieldDeclaration.openedValueTypes(
    lower: QueryFieldDeclaration
): DeclarationValue<Set<QueryValueType>> {
    val opensOpaqueValue = valueTypes === DeclarationValue.Unset &&
        kind == DeclarationValue.Set(QueryValueKind.OBJECT) &&
        lower.kind == DeclarationValue.Set(QueryValueKind.UNKNOWN)
    return if (opensOpaqueValue) DeclarationValue.Set(setOf(QueryValueType.OBJECT)) else valueTypes
}

private fun DeclarationValue<MaskRule>.mergeMaskRule(
    higher: DeclarationValue<MaskRule>,
    field: QueryField,
): DeclarationValue<MaskRule> {
    if (this is DeclarationValue.Unset) return higher
    if (higher is DeclarationValue.Unset) return this
    if ((this as DeclarationValue.Set).value == (higher as DeclarationValue.Set).value) return this
    throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.maskRule].")
}

private fun <T> DeclarationValue<T>.merge(
    higher: DeclarationValue<T>,
    field: QueryField,
    leaf: String,
    rejectDifferent: Boolean,
): DeclarationValue<T> {
    if (higher === DeclarationValue.Unset) {
        return this
    }
    if (rejectDifferent && this is DeclarationValue.Set && higher is DeclarationValue.Set) {
        if (value != higher.value) {
            throw QuerySchemaConflictException("Conflicting query schema declaration: [$field.$leaf].")
        }
    }
    return higher
}

internal fun <T> DeclarationValue<T>.valueOr(default: T): T = when (this) {
    is DeclarationValue.Set -> value
    DeclarationValue.Unset -> default
}

private fun QueryFieldDeclaration.mergeProperties(
    higher: QueryFieldDeclaration,
    field: QueryField,
    rejectDifferent: Boolean,
): DeclarationValue<Map<String, QueryFieldDeclaration>> {
    val incoming = higher.properties.valueOr(emptyMap())
    val merged = properties.valueOr(emptyMap()).toMutableMap()
    if (rejectDifferent) {
        // Separate declarations at the same priority must preserve protection in either source order.
        merged.replaceAll { name, child ->
            if (name in incoming) child else higher.inheritDynamicMasks(child, QueryField("${field.path}.$name"))
        }
    }
    incoming.forEach { (name, child) ->
        val childField = QueryField("${field.path}.$name")
        merged[name] = merged[name]?.merge(child, childField, rejectDifferent) ?: inheritDynamicMasks(child, childField)
    }
    return if (merged.isEmpty() && higher.properties === DeclarationValue.Unset) {
        properties
    } else {
        DeclarationValue.Set(
            merged
        )
    }
}

private fun QueryFieldDeclaration.inheritDynamicMasks(child: QueryFieldDeclaration, field: QueryField): QueryFieldDeclaration =
    additionalProperties.valueOr(null)?.takeIf(QueryFieldDeclaration::hasMasks)?.merge(child, field, false) ?: child

private fun DeclarationValue<QueryFieldDeclaration?>.replaceStructure(
    higher: DeclarationValue<QueryFieldDeclaration?>,
    field: QueryField,
    leaf: String,
    rejectDifferent: Boolean,
): DeclarationValue<QueryFieldDeclaration?> {
    val merged = merge(higher, field, leaf, rejectDifferent)
    val previous = valueOr(null) ?: return merged
    if (higher === DeclarationValue.Unset) return merged
    return DeclarationValue.Set(previous.preserveMasks(merged.valueOr(null), field))
}

private fun preserveAlternativeMasks(
    previous: List<QueryFieldDeclaration>,
    replacements: List<QueryFieldDeclaration>,
    field: QueryField,
): List<QueryFieldDeclaration> {
    val result = replacements.toMutableList()
    previous.filter(QueryFieldDeclaration::hasMasks).forEach { protected ->
        val candidates = result.indices.filter { protected.compatibleMaskShape(result[it]) }
        if (candidates.size != 1) {
            throw QuerySchemaConflictException(
                "Ambiguous or missing protected alternative: [$field]."
            )
        }
        val index = candidates.single()
        result[index] = protected.preserveMasks(result[index], field)!!
    }
    return result
}

internal fun QueryFieldDeclaration.hasMasks(): Boolean = maskRule is DeclarationValue.Set ||
    properties.valueOr(emptyMap()).values.any(QueryFieldDeclaration::hasMasks) ||
    items.valueOr(null)?.hasMasks() == true || additionalProperties.valueOr(null)?.hasMasks() == true ||
    alternatives.valueOr(emptyList()).any(QueryFieldDeclaration::hasMasks)

private fun QueryFieldDeclaration.compatibleMaskShape(other: QueryFieldDeclaration): Boolean {
    if (inferredKind() != other.inferredKind() || maskRule is DeclarationValue.Set && !other.isMaskStringDomain()) return false
    if (properties.valueOr(emptyMap()).any { (name, child) ->
            child.hasMasks() && (other.properties.valueOr(emptyMap())[name]?.let(child::compatibleMaskShape) != true)
        }
    ) {
        return false
    }
    if (items.valueOr(null)?.takeIf { it.hasMasks() }?.let { child ->
            other.items.valueOr(null)?.let(child::compatibleMaskShape) != true
        } == true
    ) {
        return false
    }
    if (additionalProperties.valueOr(null)?.takeIf { it.hasMasks() }?.let { child ->
            other.additionalProperties.valueOr(null)?.let(child::compatibleMaskShape) != true
        } == true
    ) {
        return false
    }
    return alternatives.valueOr(emptyList()).filter(QueryFieldDeclaration::hasMasks).all { branch ->
        other.alternatives.valueOr(emptyList()).count(branch::compatibleMaskShape) == 1
    }
}

private fun QueryFieldDeclaration.preserveMasks(
    replacement: QueryFieldDeclaration?,
    field: QueryField,
): QueryFieldDeclaration? {
    if (!hasMasks()) return replacement
    if (replacement == null || !compatibleMaskShape(replacement)) {
        throw QuerySchemaConflictException("Structure replacement loses query schema protection: [$field].")
    }
    val children = replacement.properties.valueOr(emptyMap()).mapValues { (name, child) ->
        if (name in properties.valueOr(emptyMap())) {
            child
        } else {
            inheritDynamicMasks(
                child,
                QueryField("${field.path}.$name")
            )
        }
    }.toMutableMap()
    properties.valueOr(emptyMap()).forEach { (name, child) ->
        child.preserveMasks(children[name], field)?.let { children[name] = it }
    }
    return replacement.copy(
        maskRule = maskRule.mergeMaskRule(replacement.maskRule, field),
        properties = if (children.isEmpty()) replacement.properties else DeclarationValue.Set(children),
        items = if (items.valueOr(null)?.hasMasks() == true) {
            DeclarationValue.Set(
                items.valueOr(null)!!.preserveMasks(replacement.items.valueOr(null), field)
            )
        } else {
            replacement.items
        },
        additionalProperties = if (additionalProperties.valueOr(null)?.hasMasks() == true) {
            DeclarationValue.Set(
                additionalProperties.valueOr(
                    null
                )!!.preserveMasks(replacement.additionalProperties.valueOr(null), field)
            )
        } else {
            replacement.additionalProperties
        },
        alternatives = if (alternatives.valueOr(emptyList()).any(QueryFieldDeclaration::hasMasks)) {
            DeclarationValue.Set(
                preserveAlternativeMasks(
                    alternatives.valueOr(emptyList()),
                    replacement.alternatives.valueOr(emptyList()),
                    field
                )
            )
        } else {
            replacement.alternatives
        },
    )
}

internal fun QueryFieldDeclaration.inferredKind(): QueryValueKind = kind.valueOr(
    when {
        alternatives.valueOr(emptyList()).isNotEmpty() -> QueryValueKind.UNION
        items.valueOr(null) != null -> QueryValueKind.ARRAY
        valueTypes.valueOr(emptySet()) == setOf(QueryValueType.OBJECT) || properties is DeclarationValue.Set ||
            additionalProperties.valueOr(null) != null -> QueryValueKind.OBJECT
        valueTypes.valueOr(emptySet()).isNotEmpty() -> QueryValueKind.SCALAR
        else -> QueryValueKind.UNKNOWN
    },
)

internal fun QueryFieldDeclaration.isMaskStringDomain(): Boolean = when (inferredKind()) {
    QueryValueKind.SCALAR -> valueTypes.valueOr(emptySet()) == setOf(QueryValueType.STRING)
    QueryValueKind.ARRAY -> items.valueOr(null)?.isMaskStringDomain() == true
    QueryValueKind.UNION -> alternatives.valueOr(
        emptyList()
    ).all { it.inferredKind() == QueryValueKind.NULL || it.isMaskStringDomain() }
    else -> false
}
