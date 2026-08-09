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

package me.ahoo.wow.query.backend

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant
import java.util.Collections

@ExperimentalQueryBackendApi
data class QueryBackendExecutionOptions(
    val deadline: Instant?,
    val maxReturnedRecords: Long?,
    val maxScannedRecords: Long? = null,
    val maxPageWindow: Long? = null,
    val maxCandidateBuckets: Int? = null,
    val maxReturnedBuckets: Int? = null,
    val maxCursorPages: Int? = null,
    val allowDiskUse: Boolean = false,
) {
    init {
        require(maxReturnedRecords == null || maxReturnedRecords > 0) {
            "Maximum returned records must be positive."
        }
        require(maxScannedRecords == null || maxScannedRecords > 0) {
            "Maximum scanned records must be positive."
        }
        require(maxPageWindow == null || maxPageWindow > 0) {
            "Maximum page window must be positive."
        }
        require(maxCandidateBuckets == null || maxCandidateBuckets > 0) {
            "Maximum candidate buckets must be positive."
        }
        require(maxReturnedBuckets == null || maxReturnedBuckets > 0) {
            "Maximum returned buckets must be positive."
        }
        require(maxCursorPages == null || maxCursorPages > 0) {
            "Maximum cursor pages must be positive."
        }
    }

    constructor(deadline: Instant?, maxReturnedRecords: Long?) : this(
        deadline,
        maxReturnedRecords,
        null,
        null,
        null,
        null,
        null,
        false,
    )
}

@ExperimentalQueryBackendApi
data class BackendRecord(
    val identity: String,
    val document: NormalizedValue.ObjectValue,
    val completeness: BackendRecordCompleteness,
) {
    init {
        require(identity.isNotBlank()) { "Backend record identity must not be blank." }
    }
}

@ExperimentalQueryBackendApi
enum class BackendRecordCompleteness {
    COMPLETE,
    UNKNOWN,
}

@ExperimentalQueryBackendApi
enum class BackendTotalRelation {
    EXACT,
    LOWER_BOUND,
    UNKNOWN,
}

@ExperimentalQueryBackendApi
enum class BackendPageConsistency {
    SAME_INPUT,
    INDEPENDENT,
    UNKNOWN,
}

@ExperimentalQueryBackendApi
class BackendPage(
    records: Iterable<BackendRecord>,
    val total: Long,
    val totalRelation: BackendTotalRelation,
    val consistency: BackendPageConsistency,
) {
    val records: List<BackendRecord> = Collections.unmodifiableList(records.toList())

    init {
        require(total >= 0) { "Backend page total must not be negative." }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is BackendPage &&
            records == other.records &&
            total == other.total &&
            totalRelation == other.totalRelation &&
            consistency == other.consistency

    override fun hashCode(): Int {
        var result = records.hashCode()
        result = 31 * result + total.hashCode()
        result = 31 * result + totalRelation.hashCode()
        result = 31 * result + consistency.hashCode()
        return result
    }
}

/** Storage SPI for validated, backend-neutral record plans. */
@ExperimentalQueryBackendApi
interface RecordQueryBackend {
    fun single(plan: BackendSingleQueryPlan, options: QueryBackendExecutionOptions): Mono<BackendRecord>

    fun stream(plan: BackendStreamQueryPlan, options: QueryBackendExecutionOptions): Flux<BackendRecord>

    fun page(plan: BackendPageQueryPlan, options: QueryBackendExecutionOptions): Mono<BackendPage> =
        Mono.error(QueryBackendException(QueryBackendFailureKind.UNSUPPORTED))

    fun count(plan: BackendCountQueryPlan, options: QueryBackendExecutionOptions): Mono<Long>
}

@ExperimentalQueryBackendApi
class QueryBackendException(
    val kind: QueryBackendFailureKind,
    cause: Throwable? = null,
) : IllegalStateException("Query backend failure: $kind", cause)

@ExperimentalQueryBackendApi
enum class QueryBackendFailureKind {
    UNAVAILABLE,
    TIMEOUT,
    BUDGET_EXCEEDED,
    INCOMPLETE_RESULT,
    MAPPING_FAILURE,
    UNSUPPORTED,
}
