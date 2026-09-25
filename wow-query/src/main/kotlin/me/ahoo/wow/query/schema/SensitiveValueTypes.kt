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

import com.fasterxml.jackson.annotation.JsonValue
import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.query.annotation.Sensitive
import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.infra.reflection.MergedAnnotation.Companion.toMergedAnnotation

/*
 * Design §5.8: one value keeps one sensitivity level wherever it appears. Consistency comes from structure, not from
 * guessing which paths hold the same value: `@Sensitive` on a value type (a type serialized as a string) protects
 * every property declared with it, in states and event payloads alike, and a property may only tighten that level.
 */

private val log = KotlinLogging.logger { }

/**
 * The effective sensitivity of this member: its own `@Sensitive` (directly or through a meta-annotation), else the
 * one of its [value type][QueryMemberFact.valueType].
 *
 * @throws QuerySchemaConflictException when the member declares two, when its value type is annotated but is not
 * serialized as a string, or when the member's level is looser than its type's.
 */
internal fun QueryMemberFact.effectiveSensitive(): Sensitive? {
    val declared = annotations.flatMap { annotation ->
        listOf(annotation) + annotation.annotationClass.toMergedAnnotation().mergedAnnotations
    }.filterIsInstance<Sensitive>().distinct()
    if (declared.size > 1) {
        throw QuerySchemaConflictException("Multiple effective @Sensitive annotations are not allowed.")
    }
    val own = declared.singleOrNull()
    val inherited = valueType.sensitiveValueType() ?: return own
    if (own == null) return inherited
    if (own.level < inherited.level) {
        throw QuerySchemaConflictException(
            "Sensitive member [$name] loosens its value type [${valueType.name}] from ${inherited.level} to ${own.level}."
        )
    }
    return own
}

/** Whether the member's JVM value may carry a mask: a string, or a value type whose `@Sensitive` protects it. */
internal fun QueryMemberFact.holdsMaskableValue(): Boolean =
    type == String::class.java || valueType.sensitiveValueType() != null

/**
 * The `@Sensitive` of this class when it is a sensitive value type, or `null` when it carries none.
 *
 * @throws QuerySchemaConflictException when the class carries `@Sensitive` but does not serialize as a string: only
 * a Kotlin value class or a type with a `@JsonValue` member can.
 */
internal fun Class<*>.sensitiveValueType(): Sensitive? {
    if (isPlatformType()) return null
    val sensitive = (listOf(*annotations) + kotlin.toMergedAnnotation().mergedAnnotations)
        .filterIsInstance<Sensitive>().distinct()
    if (sensitive.isEmpty()) return null
    if (sensitive.size > 1) {
        throw QuerySchemaConflictException("Multiple effective @Sensitive annotations on value type [$name].")
    }
    if (!kotlin.isValue && !hasJsonValue()) {
        throw QuerySchemaConflictException(
            "@Sensitive value type [$name] must serialize as a string: a value class or a type with @JsonValue."
        )
    }
    return sensitive.single()
}

private fun Class<*>.isPlatformType(): Boolean =
    isPrimitive || isArray || name.startsWith("java.") || name.startsWith("kotlin.")

private fun Class<*>.hasJsonValue(): Boolean =
    generateSequence(this) { it.superclass }.any { type ->
        type.declaredMethods.any { it.isAnnotationPresent(JsonValue::class.java) } ||
            type.declaredFields.any { it.isAnnotationPresent(JsonValue::class.java) }
    }

/** One protected-or-not leaf of a model payload: its path, leaf name, value type and effective level. */
private data class SensitivityLeaf(
    val path: String,
    val name: String,
    val valueType: Class<*>,
    val level: SensitivityLevel?
)

private fun QueryTypeFact.leaves(path: String): List<SensitivityLeaf> = buildList {
    member?.let { member ->
        add(SensitivityLeaf(path, path.substringAfterLast('.'), member.valueType, member.effectiveSensitive()?.level))
    }
    properties.forEach { (name, child) -> addAll(child.leaves("$path.$name")) }
    items?.let { addAll(it.leaves(path)) }
    additionalProperties?.let { addAll(it.leaves("$path.{key}")) }
    alternatives.forEach { addAll(it.leaves(path)) }
}.distinct()

/**
 * Warns (never fails, design §5.8) about each event payload field whose leaf name and value type match a state field
 * while exactly one of the two is sensitive: likely the same value, protected in one model only.
 */
internal fun warnInconsistentSensitivity(state: QueryTypeFact, statePath: String, events: Map<String, QueryTypeFact>) {
    val stateLeaves = state.leaves(statePath).groupBy { it.name to it.valueType }
    events.forEach { (eventPath, event) ->
        event.leaves(eventPath).forEach { eventLeaf ->
            stateLeaves[eventLeaf.name to eventLeaf.valueType].orEmpty()
                .filter { (it.level == null) != (eventLeaf.level == null) }
                .forEach { stateLeaf ->
                    log.warn {
                        "Query schema sensitivity differs for one value: [${stateLeaf.path}] is " +
                            "${stateLeaf.level ?: "not sensitive"}, [${eventLeaf.path}] is " +
                            "${eventLeaf.level ?: "not sensitive"}. Annotate both, or declare the value type @Sensitive."
                    }
                }
        }
    }
}
