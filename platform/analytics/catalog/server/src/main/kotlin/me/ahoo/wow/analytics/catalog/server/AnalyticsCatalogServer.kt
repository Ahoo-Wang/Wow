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

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * The analytics catalog service on the Wow host (roadmap 9.3b, decisions D238, D241, D251): the control plane of the
 * analytics platform, the warehouse's data-source catalog, centrally managed. The host finds the `source` model through `analytics-catalog-domain`'s binding index and
 * serves the analytics catalog context's routes:
 *
 * - the catalog is the warehouse's own metadata ([ClickHouseWarehouseCatalog]): each table or view of the configured
 *   databases is a source; [CatalogWatcher] syncs a table's source when its listing or its `wow_catalog` rows change,
 *   which adopts the trusted row of a Wow table;
 * - an editor (an analyst) drafts an analyst's table, revises its scope and references and reviews its string columns,
 *   an approver marks it reviewed (`POST /analytics-catalog/source/{id}/...`) and accepts a Wow source's lower
 *   protection;
 * - the sources are read through the V10 query routes (`POST /analytics-catalog/source/snapshot/query`), on a backend
 *   that serves snapshot queries.
 *
 * Who calls is [AnalyticsCatalogCallers]'s to say; a deployment puts CoSec's resolver in its place. The data plane
 * (dataset queries, the Sink) never calls this service on its own path (D251 ④).
 */
@SpringBootApplication(proxyBeanMethods = false)
class AnalyticsCatalogServer

fun main(args: Array<String>) {
    runApplication<AnalyticsCatalogServer>(*args)
}
