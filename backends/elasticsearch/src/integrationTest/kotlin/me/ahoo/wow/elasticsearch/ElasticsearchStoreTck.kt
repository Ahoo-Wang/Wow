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

package me.ahoo.wow.elasticsearch

import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StorePool
import me.ahoo.wow.tck.StoreTck

/**
 * The whole Elasticsearch store, opened through its provider as the host opens it: it holds snapshots only, so it runs
 * the snapshot store's contract, and refuses the log and the control plane.
 */
class ElasticsearchStoreTck : StoreTck() {
    override fun config(): StoreConfig = ElasticsearchFixture.config()

    /** It serves queries and says its pool. */
    override val facets: List<Class<*>> = listOf(StoreQueries::class.java, StorePool::class.java)
}
