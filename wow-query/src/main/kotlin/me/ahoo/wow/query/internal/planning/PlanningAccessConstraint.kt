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
import me.ahoo.wow.query.internal.schema.QUERY_FIELD_ID_COMPARATOR
import me.ahoo.wow.query.internal.schema.QueryFieldId
import java.util.Collections

internal sealed interface FieldAccess {
    fun permits(field: QueryFieldId): Boolean

    data object Unrestricted : FieldAccess {
        override fun permits(field: QueryFieldId): Boolean = true
    }

    data object DenyAll : FieldAccess {
        override fun permits(field: QueryFieldId): Boolean = false
    }

    class AllowList(fields: Iterable<QueryFieldId>) : FieldAccess {
        val fields: Set<QueryFieldId> = Collections.unmodifiableSet(
            LinkedHashSet(fields.sortedWith(QUERY_FIELD_ID_COMPARATOR)),
        )

        override fun permits(field: QueryFieldId): Boolean = field in fields

        override fun equals(other: Any?): Boolean = this === other || other is AllowList && fields == other.fields

        override fun hashCode(): Int = fields.hashCode()
    }
}

internal sealed interface SearchScopeAccess {
    fun permits(scope: SearchScopeId): Boolean

    data object Unrestricted : SearchScopeAccess {
        override fun permits(scope: SearchScopeId): Boolean = true
    }

    data object DenyAll : SearchScopeAccess {
        override fun permits(scope: SearchScopeId): Boolean = false
    }

    class AllowList(scopes: Iterable<SearchScopeId>) : SearchScopeAccess {
        val scopes: Set<SearchScopeId> = Collections.unmodifiableSet(
            LinkedHashSet(scopes.sortedBy(SearchScopeId::value)),
        )

        override fun permits(scope: SearchScopeId): Boolean = scope in scopes

        override fun equals(other: Any?): Boolean = this === other || other is AllowList && scopes == other.scopes

        override fun hashCode(): Int = scopes.hashCode()
    }
}

internal sealed interface NativeBackendAccess {
    fun permits(backendId: BackendId): Boolean

    data object Unrestricted : NativeBackendAccess {
        override fun permits(backendId: BackendId): Boolean = true
    }

    data object DenyAll : NativeBackendAccess {
        override fun permits(backendId: BackendId): Boolean = false
    }

    class AllowList(backends: Iterable<BackendId>) : NativeBackendAccess {
        val backends: Set<BackendId> = Collections.unmodifiableSet(
            LinkedHashSet(backends.sortedBy(BackendId::value)),
        )

        override fun permits(backendId: BackendId): Boolean = backendId in backends

        override fun equals(other: Any?): Boolean = this === other || other is AllowList && backends == other.backends

        override fun hashCode(): Int = backends.hashCode()
    }
}

internal data class QueryFieldConstraint(
    val filterFields: FieldAccess = FieldAccess.Unrestricted,
    val searchScopes: SearchScopeAccess = SearchScopeAccess.Unrestricted,
    val nativeBackends: NativeBackendAccess = NativeBackendAccess.Unrestricted,
    val projectionFields: FieldAccess = FieldAccess.Unrestricted,
    val sortFields: FieldAccess = FieldAccess.Unrestricted,
    val analyticsDimensionFields: FieldAccess = FieldAccess.Unrestricted,
    val analyticsMetricFields: FieldAccess = FieldAccess.Unrestricted,
) {
    companion object {
        val DenyAll: QueryFieldConstraint = QueryFieldConstraint(
            filterFields = FieldAccess.DenyAll,
            searchScopes = SearchScopeAccess.DenyAll,
            nativeBackends = NativeBackendAccess.DenyAll,
            projectionFields = FieldAccess.DenyAll,
            sortFields = FieldAccess.DenyAll,
            analyticsDimensionFields = FieldAccess.DenyAll,
            analyticsMetricFields = FieldAccess.DenyAll,
        )
    }
}
