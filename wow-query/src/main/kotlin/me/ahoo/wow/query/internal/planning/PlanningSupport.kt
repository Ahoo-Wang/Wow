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

package me.ahoo.wow.query.internal.planning

import me.ahoo.wow.query.internal.normalization.BackendId
import me.ahoo.wow.query.internal.normalization.SearchScopeId
import me.ahoo.wow.query.internal.plan.RequiredCapabilities
import me.ahoo.wow.query.internal.plan.SemanticTier
import me.ahoo.wow.query.internal.rejection.QueryRejectionCategory
import me.ahoo.wow.query.internal.rejection.QueryRejectionCode
import me.ahoo.wow.query.internal.rejection.QueryRejectionPath
import me.ahoo.wow.query.internal.rejection.rejectQuery
import me.ahoo.wow.query.internal.schema.FieldCapability
import me.ahoo.wow.query.internal.schema.QueryDocumentSchema
import me.ahoo.wow.query.internal.schema.QueryFieldId

internal fun mergeCapabilities(vararg sources: RequiredCapabilities): RequiredCapabilities {
    val fields = linkedMapOf<QueryFieldId, MutableSet<FieldCapability>>()
    val searches = linkedSetOf<SearchScopeId>()
    var native: BackendId? = null
    sources.forEach { source ->
        source.fieldRequirements.forEach { (field, requirements) ->
            fields.getOrPut(field, ::linkedSetOf).addAll(requirements)
        }
        searches.addAll(source.searchRequirements)
        source.nativeBackend?.let { backend ->
            if (native != null && native != backend) {
                rejectQuery(
                    QueryRejectionCategory.INVALID_QUERY,
                    QueryRejectionPath.ROOT.property("input"),
                    QueryRejectionCode.NATIVE_BACKEND_CONFLICT,
                )
            }
            native = backend
        }
    }
    return RequiredCapabilities(fields.mapValues { it.value.toSet() }, searches, native)
}

internal fun requireFieldCapability(
    schema: QueryDocumentSchema,
    field: QueryFieldId,
    capability: FieldCapability,
    path: QueryRejectionPath,
) {
    val fieldSchema = schema.fields[field] ?: rejectQuery(
        QueryRejectionCategory.UNSUPPORTED_FEATURE,
        path,
        QueryRejectionCode.FIELD_NOT_FOUND,
    )
    if (capability !in fieldSchema.capabilities) {
        rejectQuery(QueryRejectionCategory.UNSUPPORTED_FEATURE, path, QueryRejectionCode.CAPABILITY_UNAVAILABLE)
    }
}

internal fun QueryFieldId.stableKey(): String =
    when (this) {
        is QueryFieldId.System -> "0:${kind.name}"
        is QueryFieldId.Path -> "1:${segments.joinToString("\u0000")}"
    }

internal fun SemanticTier.max(other: SemanticTier): SemanticTier = if (ordinal >= other.ordinal) this else other
