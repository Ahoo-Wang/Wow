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
import me.ahoo.wow.api.query.FilterExpression
import me.ahoo.wow.api.query.MatchAllFilter
import me.ahoo.wow.query.EventsMode
import me.ahoo.wow.query.QueryCaller
import me.ahoo.wow.query.QueryPolicy
import me.ahoo.wow.query.QueryRequestException
import me.ahoo.wow.query.filter.QueryContext
import me.ahoo.wow.query.schema.QueryModel

/**
 * Keeps the catalog to the catalog's roles: a query of the catalog's own aggregates (its sources and datasets, their
 * columns, sensitivity and lineage, the control plane) by a caller with none of [AnalyticsCatalogCallers.READERS] is
 * refused ([READER_REQUIRED]); trusted in-process code, which has no caller, is not restricted.
 *
 * The published datasets themselves (the `DATASET` model, the data plane, decision D251) are not the catalog's to
 * guard: their queries go through the node's other policies as any dataset's do, each caller reading the rows of its
 * own scope (tenant, owner, space, ABAC tags) with each value masked as its lineage says. It hides no row, so its
 * events queries need not name their aggregates ([EventsMode.NONE]).
 */
class AnalyticsCatalogQueryPolicy : QueryPolicy {
    override val events: EventsMode
        get() = EventsMode.NONE

    override suspend fun evaluate(caller: QueryCaller, context: QueryContext<*>): FilterExpression {
        val who = caller.caller ?: return MatchAllFilter
        val catalog = context.aggregateType.context == AnalyticsCatalogContext.NAME &&
            context.model != QueryModel.DATASET
        if (catalog && AnalyticsCatalogCallers.rolesOf(who).none { it in AnalyticsCatalogCallers.READERS }) {
            throw QueryRequestException(
                "${who.subject} reads no source: it needs one of ${AnalyticsCatalogCallers.READERS}.",
                READER_REQUIRED,
            )
        }
        return MatchAllFilter
    }

    companion object {
        /** The code of a query by a caller without a reader's role. */
        const val READER_REQUIRED: String = "READER_REQUIRED"
    }
}
