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

package me.ahoo.wow.cocache

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.manifest.ClassPathContracts
import me.ahoo.wow.manifest.ManifestFragment
import me.ahoo.wow.spi.json.WowJson

/**
 * The state views the peer contracts on a class path name: every api jar carries its contexts' contract
 * (`META-INF/wow/contract.json`), which names the `<State>View` it generates of each state it publishes. A service's
 * own api is on its class path too, so its own aggregates' views are among them.
 */
internal object ContractViews {
    /** The view class of each aggregate type [contracts] name a view of. */
    fun of(contracts: List<ManifestFragment>, classLoader: ClassLoader): Map<AggregateType, Class<*>> =
        contracts.flatMap { contract ->
            contract.manifest.contexts.flatMap { context ->
                context.aggregates.mapNotNull { aggregate ->
                    aggregate.state.view?.let { view ->
                        AggregateType(context.name, aggregate.name) to Class.forName(view, false, classLoader)
                    }
                }
            }
        }.toMap()

    /** The view class of each aggregate type the contracts on [classLoader] name a view of. */
    fun onClassPath(classLoader: ClassLoader): Map<AggregateType, Class<*>> =
        of(
            ClassPathContracts.load(classLoader) { bytes -> WowJson.mapper.readValue(bytes, Map::class.java) },
            classLoader
        )
}
