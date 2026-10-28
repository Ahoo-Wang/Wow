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

package me.ahoo.wow.spring.boot

import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.runtime.store.StoreTypes
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.spi.store.StoreProvider
import me.ahoo.wow.tck.StoreTck

/** The host's store in memory, opened through its built-in provider, against every port's contract. */
class MemoryStoreTck : StoreTck() {
    override fun config(): StoreConfig = StoreConfig.of(name = "tck", type = StoreTypes.MEMORY)

    /** Built into the host, not on the class path's service list; its snapshots queried, its logs scanned. */
    override fun provider(type: String): StoreProvider = MemoryStoreProvider(memoryQueries = true)

    override val facets: List<Class<*>> = listOf(StoreQueries::class.java)
}
