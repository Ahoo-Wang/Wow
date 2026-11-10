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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toSet
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContext
import me.ahoo.wow.analytics.catalog.api.AnalyticsCatalogContract
import me.ahoo.wow.analytics.catalog.domain.source.SourceState
import me.ahoo.wow.api.query.dsl.listQuery
import me.ahoo.wow.api.read.NodeQueryApi
import me.ahoo.wow.api.read.snapshots
import me.ahoo.wow.query.read.QueryNotServedException

/**
 * The sources the catalog holds, from the sources' snapshots, read as a job that reads the whole model, as trusted
 * code, the catalog's own, in the catalog's tenant. A node whose backend serves no snapshot query holds none it can
 * read, and says so in its log: the watcher then knows only the tables it sees.
 */
class QueriedCatalogSources(private val queries: () -> NodeQueryApi) : CatalogSources {
    override suspend fun present(connection: String, database: String): Set<String> {
        val sources = try {
            queries().trusted().unbounded().snapshots<SourceState>()
        } catch (_: QueryNotServedException) {
            logger.warn {
                "No snapshot query serves ${AnalyticsCatalogContract.Source.TYPE}: a table dropped while the catalog " +
                    "was down is not marked missing."
            }
            return emptySet()
        }
        return sources.list(
            listQuery {
                filter {
                    tenantId(AnalyticsCatalogContext.TENANT_ID)
                    "state.connection" eq connection
                    "state.database" eq database
                    "state.missing" eq false
                }
            },
        ).map { it.state.table }.toSet()
    }

    private companion object {
        val logger = KotlinLogging.logger {}
    }
}
