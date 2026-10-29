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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContract
import me.ahoo.wow.analytics.catalog.api.source.SourceKind
import me.ahoo.wow.analytics.catalog.domain.warehouse.KnownAggregates
import me.ahoo.wow.api.query.dsl.singleQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.dynamic
import me.ahoo.wow.query.read.QueryNotServedException
import tools.jackson.databind.node.ObjectNode

/**
 * The aggregates the catalog knows, from the sources' snapshots: an aggregate some Wow source of the catalog is of,
 * read as trusted code, the catalog's own, in the catalog's tenant. A node whose backend serves no snapshot query knows
 * of none, and says so in its log.
 */
class QueriedKnownAggregates(private val queries: () -> NodeQueryApi) : KnownAggregates {
    override suspend fun knows(context: String, aggregate: String): Boolean {
        val sources = try {
            queries().trusted().snapshots(SOURCE).dynamic<ObjectNode>()
        } catch (_: QueryNotServedException) {
            return none()
        }
        val found = sources.single(
            singleQuery {
                filter {
                    tenantId(AnalyticsCatalogContext.TENANT_ID)
                    "state.kind" eq SourceKind.WOW.name
                    "state.enrichment.context" eq context
                    "state.enrichment.aggregate" eq aggregate
                }
                projection { include("aggregateId") }
            },
        )
        return found != null
    }

    private fun none(): Boolean {
        logger.warn { "No snapshot query serves $SOURCE: the catalog knows no aggregate to refer to." }
        return false
    }

    private companion object {
        val SOURCE = AnalyticsCatalogContract.Source.TYPE
        val logger = KotlinLogging.logger {}
    }
}
