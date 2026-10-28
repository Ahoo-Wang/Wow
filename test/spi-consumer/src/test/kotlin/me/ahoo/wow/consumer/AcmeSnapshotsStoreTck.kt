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

package me.ahoo.wow.consumer

import me.ahoo.wow.query.store.StoreQueries
import me.ahoo.wow.spi.store.StoreConfig
import me.ahoo.wow.tck.StoreTck

/**
 * The third-party store against the whole store contract, opened through its provider from the class path as the host
 * opens it: the snapshot store's specs, its query facet, and its refusal of the log and the control plane. The log
 * specs skip: it holds no log, by design (see [AcmeSnapshotsProvider]).
 */
class AcmeSnapshotsStoreTck : StoreTck() {
    override fun config(): StoreConfig = StoreConfig.of(name = "tck", type = AcmeSnapshotsProvider.TYPE)

    override val facets: List<Class<*>> = listOf(StoreQueries::class.java)
}
