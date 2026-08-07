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

package me.ahoo.wow.query.internal.gateway

import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.query.internal.admission.AdmissionBudget
import me.ahoo.wow.query.internal.admission.QueryAdmissionLimits
import me.ahoo.wow.query.internal.admission.RawValueSnapshotter
import me.ahoo.wow.query.internal.execution.BackendRecord
import me.ahoo.wow.query.internal.execution.BackendRecordCompleteness
import me.ahoo.wow.query.internal.execution.QueryBackendException
import me.ahoo.wow.query.internal.execution.QueryBackendFailureKind
import me.ahoo.wow.query.internal.normalization.LogicalField
import me.ahoo.wow.query.internal.normalization.NormalizedProjection
import me.ahoo.wow.query.internal.normalization.NormalizedValue
import me.ahoo.wow.query.internal.normalization.PathBasis
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import java.util.LinkedHashMap

internal class LegacyResultSnapshotter(
    private val limits: QueryAdmissionLimits,
) {
    private val valueSnapshotter = RawValueSnapshotter(limits)

    @Suppress("TooGenericExceptionCaught")
    fun snapshot(query: LegacyDynamicCompiledQuery, source: DynamicDocument): BackendRecord {
        try {
            return snapshotResult(query, source)
        } catch (error: RuntimeException) {
            throw QueryBackendException(QueryBackendFailureKind.MAPPING_FAILURE, error)
        }
    }

    private fun snapshotResult(query: LegacyDynamicCompiledQuery, source: DynamicDocument): BackendRecord {
        val frozen = requireNotNull(
            valueSnapshotter.snapshot(
                source,
                QueryRejectionPath.ROOT.property("result"),
                AdmissionBudget(limits),
            ) as? NormalizedValue.ObjectValue,
        ) {
            "Legacy query result must be an object."
        }
        val identity = requireNotNull((frozen.values[query.identityField] as? NormalizedValue.Text)?.value) {
            "Legacy query result is missing a string identity."
        }
        return BackendRecord(
            identity,
            query.outputProjection.applyTo(frozen),
            BackendRecordCompleteness.UNKNOWN,
        )
    }
}

private fun NormalizedProjection.applyTo(source: NormalizedValue.ObjectValue): NormalizedValue.ObjectValue =
    when (this) {
        NormalizedProjection.All -> source
        is NormalizedProjection.Include -> source.include(fields.values.map(LogicalField.Path::rootSegments))
        is NormalizedProjection.Exclude -> source.exclude(fields.values.map(LogicalField.Path::rootSegments))
        is NormalizedProjection.Mixed -> {
            source
                .include(include.values.map(LogicalField.Path::rootSegments))
                .exclude(exclude.values.map(LogicalField.Path::rootSegments))
        }
    }

private fun LogicalField.Path.rootSegments(): List<String> {
    if (basis != PathBasis.ROOT) {
        rejectLegacyLowering()
    }
    return segments
}

private fun NormalizedValue.ObjectValue.include(paths: List<List<String>>): NormalizedValue.ObjectValue {
    val result = LinkedHashMap<String, NormalizedValue>()
    values.forEach { (key, value) ->
        val matching = paths.filter { path -> path.firstOrNull() == key }
        if (matching.any { path -> path.size == 1 }) {
            result[key] = value
        } else if (matching.isNotEmpty()) {
            result[key] = value.includeNested(matching.map { path -> path.drop(1) })
        }
    }
    return NormalizedValue.ObjectValue(result)
}

private fun NormalizedValue.includeNested(paths: List<List<String>>): NormalizedValue =
    when (this) {
        is NormalizedValue.ObjectValue -> include(paths)
        is NormalizedValue.ListValue -> NormalizedValue.ListValue(values.map { value -> value.includeNested(paths) })
        else -> throw IllegalArgumentException("Legacy result does not match the requested projection path.")
    }

private fun NormalizedValue.ObjectValue.exclude(paths: List<List<String>>): NormalizedValue.ObjectValue {
    val result = LinkedHashMap<String, NormalizedValue>()
    values.forEach { (key, value) ->
        val matching = paths.filter { path -> path.firstOrNull() == key }
        if (matching.none { path -> path.size == 1 }) {
            val nested = matching.filter { path -> path.size > 1 }.map { path -> path.drop(1) }
            result[key] = if (nested.isEmpty()) value else value.excludeNested(nested)
        }
    }
    return NormalizedValue.ObjectValue(result)
}

private fun NormalizedValue.excludeNested(paths: List<List<String>>): NormalizedValue =
    when (this) {
        is NormalizedValue.ObjectValue -> exclude(paths)
        is NormalizedValue.ListValue -> NormalizedValue.ListValue(values.map { value -> value.excludeNested(paths) })
        else -> this
    }
