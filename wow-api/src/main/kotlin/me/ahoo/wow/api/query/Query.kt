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

package me.ahoo.wow.api.query

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Duration

data class Query @JvmOverloads constructor(
    val filter: QueryExpression = MatchAll,
    val projection: QueryProjection = QueryProjection.All,
    val sort: List<QuerySort> = emptyList(),
    val scope: QueryScope = QueryScope(),
    val budget: QueryBudget = QueryBudget()
)

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(QueryProjection.All::class, name = "all"),
    JsonSubTypes.Type(QueryProjection.Include::class, name = "include"),
    JsonSubTypes.Type(QueryProjection.Exclude::class, name = "exclude")
)
sealed interface QueryProjection {
    data object All : QueryProjection

    data class Include(val fields: Set<LogicalField>) : QueryProjection {
        init {
            require(fields.isNotEmpty()) { "Included fields cannot be empty." }
        }
    }

    data class Exclude(val fields: Set<LogicalField>) : QueryProjection {
        init {
            require(fields.isNotEmpty()) { "Excluded fields cannot be empty." }
        }
    }

    /** Compatibility-only projection for legacy backend field paths. */
    data class Legacy(
        val include: List<String> = emptyList(),
        val exclude: List<String> = emptyList()
    ) : QueryProjection {
        init {
            require(include.isNotEmpty() || exclude.isNotEmpty()) { "Legacy projection cannot be empty." }
            require(include.isEmpty() || exclude.isEmpty()) { "Legacy projection cannot mix include and exclude." }
        }
    }
}

enum class QuerySortDirection {
    ASC,
    DESC
}

data class QuerySort(
    val field: LogicalField,
    val direction: QuerySortDirection
)

enum class DeletionScope {
    DEFAULT,
    ACTIVE,
    DELETED,
    ALL
}

data class QueryScope @JvmOverloads constructor(
    val tenantId: String? = null,
    val ownerId: String? = null,
    val spaceId: String? = null,
    val deletion: DeletionScope = DeletionScope.DEFAULT
) {
    init {
        require(tenantId == null || tenantId.isNotBlank()) { "tenantId cannot be blank." }
        require(ownerId == null || ownerId.isNotBlank()) { "ownerId cannot be blank." }
        require(spaceId == null || spaceId.isNotBlank()) { "spaceId cannot be blank." }
    }
}

data class QueryBudget @JvmOverloads constructor(
    val timeout: Duration? = null,
    val maxRecords: Long? = null
) {
    init {
        require(timeout == null || (!timeout.isNegative && !timeout.isZero)) { "timeout must be positive." }
        require(maxRecords == null || maxRecords > 0) { "maxRecords must be positive." }
    }
}
