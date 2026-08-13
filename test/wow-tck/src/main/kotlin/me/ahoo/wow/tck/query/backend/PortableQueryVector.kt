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

package me.ahoo.wow.tck.query.backend

import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.LogicalOperator
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.QueryCapabilityId
import me.ahoo.wow.api.query.expression.QueryExpression
import me.ahoo.wow.api.query.gateway.QueryDocumentKind
import me.ahoo.wow.api.query.gateway.QueryOperation
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QuerySort
import me.ahoo.wow.api.query.gateway.RequestedQueryScope
import me.ahoo.wow.query.backend.QueryPortableFeature
import java.util.Collections

enum class PortableVectorKind {
    POSITIVE,
    NEGATIVE,
    BOUNDARY
}

enum class PortableQueryScenario {
    MISSING_VS_NULL,
    EMPTY_COLLECTION,
    EMPTY_OBJECT,
    UNICODE_LITERAL,
    CASE_SENSITIVE,
    CASE_INSENSITIVE,
    ENUM_VALUE,
    INSTANT_VALUE,
    PROJECTION,
    STABLE_TIE_SORT,
    EMPTY_SINGLE
}

enum class PortableLifecycleCase {
    BACKPRESSURE,
    CANCEL,
    DEADLINE
}

sealed interface PortableContractKey {
    data class Operator(val value: PortableOperator) : PortableContractKey

    data class Feature(val value: QueryPortableFeature) : PortableContractKey

    data class Logical(val value: LogicalOperator) : PortableContractKey

    data class SystemField(val value: LogicalField) : PortableContractKey

    data class Operation(val value: QueryOperation) : PortableContractKey

    data class Scenario(val value: PortableQueryScenario) : PortableContractKey

    data class Lifecycle(val value: PortableLifecycleCase) : PortableContractKey

    data class Capability(val value: QueryCapabilityId) : PortableContractKey
}

class PortableQueryExpectation(
    logicalIds: List<String>,
    val exactTotal: Long = logicalIds.size.toLong(),
    val error: QueryErrorCode? = null
) {
    val logicalIds: List<String> = immutableList(logicalIds)

    init {
        require(exactTotal >= 0) { "Exact total cannot be negative." }
        require(error != null || exactTotal >= this.logicalIds.size) {
            "Exact total cannot be smaller than the expected item count."
        }
        require(error == null || this.logicalIds.isEmpty()) { "Error expectations cannot contain result ids." }
    }

    override fun equals(other: Any?): Boolean = other is PortableQueryExpectation &&
        logicalIds == other.logicalIds && exactTotal == other.exactTotal && error == other.error

    override fun hashCode(): Int = 31 * (31 * logicalIds.hashCode() + exactTotal.hashCode()) + (error?.hashCode() ?: 0)

    override fun toString(): String =
        "PortableQueryExpectation(resultCount=${logicalIds.size}, exactTotal=$exactTotal, error=$error)"
}

class PortableQueryVector(
    val id: String,
    val key: PortableContractKey,
    val kind: PortableVectorKind,
    val expression: QueryExpression,
    expectations: Map<QueryDocumentKind, PortableQueryExpectation>,
    val requestedScope: RequestedQueryScope = RequestedQueryScope(),
    sort: List<QuerySort> = emptyList(),
    val projection: QueryProjection = QueryProjection.All
) {
    val expectations: Map<QueryDocumentKind, PortableQueryExpectation> = immutableMap(expectations)
    val sort: List<QuerySort> = immutableList(sort)

    init {
        require(ID_PATTERN.matches(id)) { "Portable query vector id is invalid." }
        require(this.expectations.isNotEmpty()) { "Portable query vector must apply to at least one document kind." }
    }

    fun expectation(documentKind: QueryDocumentKind): PortableQueryExpectation? = expectations[documentKind]

    override fun equals(other: Any?): Boolean = other is PortableQueryVector &&
        id == other.id && key == other.key && kind == other.kind && expression == other.expression &&
        expectations == other.expectations && requestedScope == other.requestedScope && sort == other.sort &&
        projection == other.projection

    override fun hashCode(): Int = listOf(
        id,
        key,
        kind,
        expression,
        expectations,
        requestedScope,
        sort,
        projection
    ).hashCode()

    override fun toString(): String =
        "PortableQueryVector(id=$id, key=$key, kind=$kind, documentKinds=${expectations.keys}, " +
            "expectedResultCounts=${expectations.values.map(PortableQueryExpectation::logicalIds).map { it.size }})"

    private companion object {
        val ID_PATTERN: Regex = Regex("[a-z0-9][a-z0-9-]{0,95}")
    }
}

private fun <T> immutableList(source: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(source))

private fun <K, V> immutableMap(source: Map<K, V>): Map<K, V> =
    Collections.unmodifiableMap(LinkedHashMap(source))
