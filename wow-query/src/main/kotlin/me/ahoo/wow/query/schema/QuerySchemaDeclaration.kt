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

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.QueryField
import me.ahoo.wow.api.query.schema.QueryModel
import me.ahoo.wow.api.query.schema.QuerySemanticType
import me.ahoo.wow.api.query.schema.QueryValueKind
import me.ahoo.wow.api.query.schema.QueryValueType
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.DESCRIPTION
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.ENUM_VALUES
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.NULLABLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.REQUIRED
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.SEMANTIC_TYPE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.TITLE
import me.ahoo.wow.query.schema.QuerySchemaDeclarationProperties.VALUE_TYPES
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode

object QuerySchemaDeclarationProperties {
    const val FIELDS = "fields"
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val ENUM_VALUES = "enumValues"
    const val VALUE_TYPES = "valueTypes"
    const val NULLABLE = "nullable"
    const val REQUIRED = "required"
    const val KIND = "kind"
    const val PROPERTIES = "properties"
    const val ITEMS = "items"
    const val ADDITIONAL_PROPERTIES = "additionalProperties"
    const val ALTERNATIVES = "alternatives"
    const val SEMANTIC_TYPE = "semanticType"
}

sealed interface DeclarationValue<out T> {
    data object Unset : DeclarationValue<Nothing>

    data class Set<T>(val value: T) : DeclarationValue<T>
}

data class QuerySchemaContext(
    val namedAggregate: NamedAggregate,
    val model: QueryModel,
)

data class QuerySchemaRegistration(
    val context: QuerySchemaContext,
    val declaration: QuerySchemaDeclaration,
)

data class QuerySchemaDeclaration(
    val fields: Map<QueryField, QueryFieldDeclaration>,
)

data class QueryFieldDeclaration(
    val title: DeclarationValue<String?> = DeclarationValue.Unset,
    val description: DeclarationValue<String?> = DeclarationValue.Unset,
    val enumValues: DeclarationValue<List<JsonNode>?> = DeclarationValue.Unset,
    val valueTypes: DeclarationValue<Set<QueryValueType>> = DeclarationValue.Unset,
    val nullable: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val required: DeclarationValue<Boolean> = DeclarationValue.Unset,
    val kind: DeclarationValue<QueryValueKind> = DeclarationValue.Unset,
    val properties: DeclarationValue<Map<String, QueryFieldDeclaration>> = DeclarationValue.Unset,
    val items: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
    val additionalProperties: DeclarationValue<QueryFieldDeclaration?> = DeclarationValue.Unset,
    val alternatives: DeclarationValue<List<QueryFieldDeclaration>> = DeclarationValue.Unset,
    val semanticType: DeclarationValue<QuerySemanticType?> = DeclarationValue.Unset,
    @get:JsonIgnore val maskRule: DeclarationValue<MaskRule> = DeclarationValue.Unset,
)

interface QuerySchemaSource {
    val priority: Int

    fun load(context: QuerySchemaContext): Flux<QuerySchemaDeclaration>

    fun refresh(context: QuerySchemaContext): Flux<QuerySchemaDeclaration> = load(context)
}

object QuerySchemaSourcePriority {
    const val JSON_SCHEMA = 100
    const val CLASSPATH = 200
    const val BEAN = 300
    const val WORKING_DIRECTORY = 400
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
    title = title.merge(higher.title, field, TITLE, rejectDifferent),
    description = description.merge(higher.description, field, DESCRIPTION, rejectDifferent),
    enumValues = enumValues.merge(higher.enumValues, field, ENUM_VALUES, rejectDifferent),
    valueTypes = valueTypes.merge(higher.valueTypes, field, VALUE_TYPES, rejectDifferent),
    nullable = nullable.merge(higher.nullable, field, NULLABLE, rejectDifferent),
    required = required.merge(higher.required, field, REQUIRED, rejectDifferent),
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
    semanticType = semanticType.merge(higher.semanticType, field, SEMANTIC_TYPE, rejectDifferent),
    maskRule = maskRule.mergeMaskRule(higher.maskRule, field),
)

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
        result[index] = checkNotNull(protected.preserveMasks(result[index], field))
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
