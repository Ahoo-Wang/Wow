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

package me.ahoo.wow.datasetregistry.server

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.query.dsl.singleQuery
import me.ahoo.wow.datasetregistry.api.DatasetRegistryContext
import me.ahoo.wow.datasetregistry.api.source.SourceKind
import me.ahoo.wow.datasetregistry.domain.catalog.KnownAggregates
import me.ahoo.wow.http.query.HttpQueries
import me.ahoo.wow.query.QueryCaller

/**
 * The aggregates the catalog knows, from the sources' snapshots: an aggregate some Wow source of the registry is of.
 * A node whose backend serves no snapshot query knows of none, and says so in its log.
 */
class QueriedKnownAggregates(private val queries: () -> HttpQueries) : KnownAggregates {
    override suspend fun knows(context: String, aggregate: String): Boolean {
        val gateway = queries().snapshots(SOURCE) ?: return none()
        val found = gateway.single(
            singleQuery {
                filter {
                    tenantId(DatasetRegistryContext.TENANT_ID)
                    "state.kind" eq SourceKind.WOW.name
                    "state.enrichment.context" eq context
                    "state.enrichment.aggregate" eq aggregate
                }
                projection { include("aggregateId") }
            },
            QueryCaller.IN_PROCESS,
        )
        return found != null
    }

    private fun none(): Boolean {
        logger.warn { "No snapshot query serves $SOURCE: the catalog knows no aggregate to refer to." }
        return false
    }

    private companion object {
        val SOURCE = AggregateType(DatasetRegistryContext.NAME, DatasetRegistryContext.SOURCE)
        val logger = KotlinLogging.logger {}
    }
}
