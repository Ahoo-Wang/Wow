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

package me.ahoo.wow.bi.expansion.plan

import me.ahoo.wow.api.query.annotation.SensitivityLevel
import me.ahoo.wow.bi.BiScriptDiagnostic
import me.ahoo.wow.bi.BiScriptDiagnosticCode
import me.ahoo.wow.bi.BiScriptMappingDecision
import me.ahoo.wow.bi.expansion.type.JsonPropertyTypeResolver
import me.ahoo.wow.bi.expansion.type.ResolvedJsonProperty
import me.ahoo.wow.bi.expansion.type.ResolvedType
import me.ahoo.wow.bi.type.ClickHouseTypeMapping.scalarMapping
import me.ahoo.wow.query.schema.QueryMemberFact
import me.ahoo.wow.query.schema.effectiveSensitivityLevel

/**
 * `@Sensitive` under [me.ahoo.wow.bi.BiScriptOptions.omitSensitiveFields]: when it is on, no expansion column holds a
 * sensitive value, neither the property's own column nor the raw JSON of a value that contains it. The query schema's
 * own rule decides what is sensitive. When it is off, nothing is omitted.
 */
internal class StateExpansionSensitivity(private val session: StateExpansionPlanningSession) {
    private val enabled: Boolean
        get() = session.options.omitSensitiveFields
    private val subtrees = HashMap<String, Boolean>()

    /** Leaves a `@Sensitive` property out, and says so in a diagnostic. */
    fun omitProperty(property: ResolvedJsonProperty, path: String): Boolean {
        if (!enabled) return false
        val level = property.sensitivityLevel(path) ?: return false
        diagnose(
            path = path,
            type = property.type,
            message = "Sensitive property [$path] ($level) is left out of the expansion columns; " +
                "only the raw state in __state still holds it.",
        )
        return true
    }

    /** Leaves out a raw JSON column of [type] at [path] when that value holds a sensitive property. */
    fun omitRaw(path: String, type: ResolvedType): Boolean {
        if (!enabled || !type.containsSensitive(mutableSetOf())) return false
        diagnose(
            path = path,
            type = type,
            message = "The raw JSON of [$path] holds sensitive properties and is left out of the expansion columns; " +
                "only the raw state in __state still holds them.",
        )
        return true
    }

    private fun diagnose(path: String, type: ResolvedType, message: String) {
        val exists = session.diagnostics.any {
            it.code == BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED && it.path == path
        }
        if (exists) return
        session.diagnostics.add(
            BiScriptDiagnostic(
                code = BiScriptDiagnosticCode.SENSITIVE_FIELD_OMITTED,
                aggregate = session.aggregate,
                path = path,
                sourceType = type.javaType.toCanonical(),
                decision = BiScriptMappingDecision.OMITTED,
                message = message,
            )
        )
    }

    private fun ResolvedJsonProperty.sensitivityLevel(path: String): SensitivityLevel? = QueryMemberFact(
        name = path,
        type = type.rawClass,
        annotations = annotations,
        valueType = valueType,
    ).effectiveSensitivityLevel()

    private fun ResolvedType.containsSensitive(visiting: MutableSet<String>): Boolean {
        val key = javaType.toCanonical()
        subtrees[key]?.let { return it }
        if (!visiting.add(key)) return false
        val sensitive = when {
            javaType.isMapLikeType -> arguments.getOrNull(1)?.containsSensitive(visiting) ?: false
            javaType.isCollectionLikeType || javaType.isArrayType ->
                arguments.firstOrNull()?.containsSensitive(visiting) ?: false

            rawClass.isPrimitive || rawClass.scalarMapping() != null || isUnsupportedPlatformObject(this) -> false
            else -> runCatching { JsonPropertyTypeResolver.resolve(this) }.getOrDefault(emptyList()).any {
                it.sensitivityLevel(it.serializedName) != null || it.type.containsSensitive(visiting)
            }
        }
        visiting.remove(key)
        subtrees[key] = sensitive
        return sensitive
    }
}
