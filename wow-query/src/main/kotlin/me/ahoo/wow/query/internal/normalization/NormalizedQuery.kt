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

package me.ahoo.wow.query.internal.normalization

import me.ahoo.wow.query.internal.analytics.AnalyticsQuery
import me.ahoo.wow.query.internal.model.QueryOperation
import me.ahoo.wow.query.internal.model.QueryResultShape
import me.ahoo.wow.query.internal.model.QueryTarget
import me.ahoo.wow.query.internal.value.NonEmptyList
import java.util.Collections

internal data class NormalizedQueryInvocation(
    val target: QueryTarget,
    val operation: QueryOperation,
    val resultShape: QueryResultShape,
    val input: NormalizedQueryInput,
)

internal sealed interface NormalizedQueryInput {
    data class Single(val query: NormalizedRecordQuery) : NormalizedQueryInput

    data class Stream(
        val query: NormalizedRecordQuery,
        val limit: Int,
    ) : NormalizedQueryInput

    data class Page(
        val query: NormalizedRecordQuery,
        val page: NormalizedPage,
    ) : NormalizedQueryInput

    data class Count(
        val userCondition: NormalizedCondition,
        val deletionScope: NormalizedDeletionScope,
    ) : NormalizedQueryInput

    data class Analytics(val query: AnalyticsQuery) : NormalizedQueryInput
}

internal class NormalizedRecordQuery(
    val userCondition: NormalizedCondition,
    val projection: NormalizedProjection,
    sort: Iterable<NormalizedSort>,
    val deletionScope: NormalizedDeletionScope,
) {
    val sort: List<NormalizedSort> = Collections.unmodifiableList(sort.toList())

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is NormalizedRecordQuery &&
            userCondition == other.userCondition &&
            projection == other.projection &&
            sort == other.sort &&
            deletionScope == other.deletionScope

    override fun hashCode(): Int {
        var result = userCondition.hashCode()
        result = 31 * result + projection.hashCode()
        result = 31 * result + sort.hashCode()
        result = 31 * result + deletionScope.hashCode()
        return result
    }
}

/** Captures whether the legacy default-active deletion rule still has to be applied. */
internal enum class NormalizedDeletionScope {
    DEFAULT_ACTIVE,
    EXPLICIT,
}

internal sealed interface NormalizedProjection {
    data object All : NormalizedProjection

    data class Include(val fields: NonEmptyList<LogicalField.Path>) : NormalizedProjection

    data class Exclude(val fields: NonEmptyList<LogicalField.Path>) : NormalizedProjection

    /** Preserved until P1-C applies result-shape and validation-mode policy. */
    data class Mixed(
        val include: NonEmptyList<LogicalField.Path>,
        val exclude: NonEmptyList<LogicalField.Path>,
    ) : NormalizedProjection
}

internal enum class NormalizedSortDirection {
    ASC,
    DESC,
}

internal data class NormalizedSort(
    val field: LogicalField,
    val direction: NormalizedSortDirection,
)

internal data class NormalizedPage(
    val index: Int,
    val size: Int,
    val offset: Long,
)
