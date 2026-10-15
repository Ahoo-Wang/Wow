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

package me.ahoo.wow.runtime.admission

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.kernel.AggregateModel

/** The aggregate models a node serves, found by their [AggregateType] (decision D92, step 1). */
@InternalWowApi
class ModelRegistry(models: Collection<AggregateModel<*>>) {
    private val byType: Map<AggregateType, AggregateModel<*>> = models.associateBy { it.type }

    init {
        require(byType.size == models.size) { "Two models share an aggregate type." }
    }

    /** The models, one per aggregate type. */
    val models: Collection<AggregateModel<*>> get() = byType.values

    /** The contexts of the models. */
    val contexts: Set<String> = byType.keys.mapTo(LinkedHashSet()) { it.context }

    /** The model of [type], or null when this node serves none. */
    fun find(type: AggregateType): AggregateModel<*>? = byType[type]
}
