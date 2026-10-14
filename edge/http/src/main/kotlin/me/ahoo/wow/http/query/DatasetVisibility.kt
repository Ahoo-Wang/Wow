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

package me.ahoo.wow.http.query

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.query.AbacQueryOptions
import me.ahoo.wow.query.AbacQueryPolicy
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.dataset.QueryDataset
import me.ahoo.wow.query.dataset.QueryDatasetScope

/**
 * Which datasets a caller sees (decision D145): those `GET /wow/dataset` lists, and those whose routes answer it; the
 * routes of a dataset the caller does not see are a 404, as if it did not exist. It hides only what the caller could
 * not read anyway, and every query is still restricted by the gateway's policies.
 */
@ExperimentalWowApi
fun interface DatasetVisibility {
    /** Whether [caller] sees [dataset]. */
    fun visible(dataset: QueryDataset, caller: QueryCaller): Boolean

    companion object {
        /**
         * What the scope policy lets the caller read (decision D129): a platform-level dataset is seen by the callers
         * of [its tenant][QueryDatasetScope.PLATFORM_TENANT] only, since any other tenant's queries of it match
         * nothing; and a caller whose principal sets an owner or a space sees only the datasets with that column,
         * since its queries of any other fail closed (`UNKNOWN_FIELD`). Every query of a dataset the caller sees is
         * restricted to its tenant, owner and space.
         */
        @JvmField
        val DEFAULT: DatasetVisibility = DatasetVisibility { dataset, caller ->
            val scope = dataset.scope
            val fixed = scope.fixedTenantId
            (fixed == null || caller.tenantId == null || caller.tenantId == fixed) &&
                (caller.principal.ownerId == null || scope.owner != null) &&
                (caller.principal.spaceId == null || scope.space != null)
        }

        /**
         * [DEFAULT], and, when [abac] requires the caller's tags, a dataset that binds tag columns is not seen by a
         * caller without an ABAC tag ([AbacQueryPolicy.tagsOf]): its queries would be forbidden.
         */
        @JvmStatic
        fun of(abac: AbacQueryOptions): DatasetVisibility {
            if (!abac.enabled || !abac.requirePrincipalTags) return DEFAULT
            val policy = AbacQueryPolicy(abac)
            return DatasetVisibility { dataset, caller ->
                DEFAULT.visible(dataset, caller) &&
                    (dataset.scope.tags.isEmpty() || policy.tagsOf(caller.attributes).isNotEmpty())
            }
        }
    }
}
