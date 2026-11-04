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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.query.policy.ReadAction
import me.ahoo.wow.query.policy.ReadAuthorization
import me.ahoo.wow.query.policy.ReadAuthorizer
import me.ahoo.wow.query.schema.QueryModel

/**
 * Keeps the catalog to the catalog's roles: a read of the catalog's own aggregates (its sources and datasets, their
 * columns, sensitivity and lineage, the control plane), a query of any of their models, a state read or a trace, by
 * a caller with none of [AnalyticsCatalogRoles.READERS] is refused as `wow.auth.forbidden` before anything is read:
 * the read is right, the caller may not make it. Trusted in-process code, which reads with no caller, is not asked.
 *
 * The published datasets themselves (the `DATASET` model, the data plane) are not the catalog's to guard: their
 * queries go through the node's other rules as any dataset's do, each caller reading the rows of its own scope
 * (tenant, owner, space) with each value masked as its lineage says.
 */
class AnalyticsCatalogReaderRole : ReadAuthorizer {
    override suspend fun authorize(caller: Caller, read: ReadAction): ReadAuthorization {
        val catalog = read.type.context == AnalyticsCatalogContext.NAME && read.model != QueryModel.DATASET
        return if (!catalog || AnalyticsCatalogRoles.rolesOf(caller).any { it in AnalyticsCatalogRoles.READERS }) {
            ReadAuthorization.Allowed
        } else {
            ReadAuthorization.Denied.of(
                "${caller.subject} reads no source: it needs one of ${AnalyticsCatalogRoles.READERS}.",
            )
        }
    }
}
