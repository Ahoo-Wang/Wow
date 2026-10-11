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

package me.ahoo.wow.query.dataset

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.modeling.MaterializedNamedAggregate
import me.ahoo.wow.modeling.materialize

/** The gateways of the datasets this instance serves, by name: what the dataset routes and applications look up. */
class DatasetQueryGateways(gateways: Collection<DatasetQueryGateway>) {
    private val byName: Map<MaterializedNamedAggregate, DatasetQueryGateway> =
        gateways.associateBy { it.dataset.namedAggregate }

    init {
        require(byName.size == gateways.size) { "Dataset gateways must have unique names." }
    }

    val all: Collection<DatasetQueryGateway>
        get() = byName.values

    /** The gateway of [namedAggregate], or `null` when this instance serves no such dataset. */
    operator fun get(namedAggregate: NamedAggregate): DatasetQueryGateway? = byName[namedAggregate.materialize()]

    companion object {
        @JvmField
        val NONE: DatasetQueryGateways = DatasetQueryGateways(emptyList())
    }
}
