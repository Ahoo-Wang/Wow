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

package me.ahoo.wow.query.read

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.read.StateView
import me.ahoo.wow.query.backend.single

/**
 * The classes of the node's aggregate models (the kernel's `AggregateModel`s, which `:query` does not see):
 * each aggregate type's state class and its events' classes by name, so the typed [me.ahoo.wow.api.read.QueryApi]
 * finds a type by its state class and decodes rows as the model's classes.
 */
@InternalWowApi
class ReadModels(models: Collection<Model>) {
    /** One aggregate [type]'s [stateType] and the class of each of its [events] by its current name, null for none. */
    @InternalWowApi
    class Model(val type: AggregateType, val stateType: Class<*>, val events: (String) -> Class<*>? = { null })

    private val byType: Map<AggregateType, Model> = models.associateBy { it.type }
    private val byState: Map<Class<*>, List<Model>> = models.groupBy { it.stateType }

    init {
        require(byType.size == models.size) { "One model per aggregate type." }
    }

    /**
     * The aggregate type whose state is of [stateType], or that the [StateView] [stateType] names: a view of the state
     * another context's api generates, which reads it whether this node or a peer serves it.
     *
     * @throws IllegalArgumentException when no model has, or several models share, that state class, or the view names
     * several aggregates.
     */
    fun typeOf(stateType: Class<*>): AggregateType {
        val viewed = StateView.typesOf(stateType)
        if (viewed.isNotEmpty()) {
            require(viewed.size == 1) { "The aggregates $viewed share the state ${stateType.name}: query them by type." }
            return viewed.single()
        }
        val models = byState[stateType].orEmpty()
        require(models.isNotEmpty()) { "No aggregate of this node has a state of ${stateType.name}." }
        require(models.size == 1) {
            "The aggregates ${models.map { it.type }} share the state ${stateType.name}: query them by type."
        }
        return models.single().type
    }

    /** The state class of [type], or null when the node has no model of it. */
    fun stateTypeOf(type: AggregateType): Class<*>? = byType[type]?.stateType

    /** The class of [type]'s event [name], or null when its model has none of that name. */
    fun eventTypeOf(type: AggregateType, name: String): Class<*>? = byType[type]?.events?.invoke(name)

    @InternalWowApi
    companion object {
        /** No model: no type is found by its state, and every row decodes as the record. */
        @JvmField
        val NONE: ReadModels = ReadModels(emptyList())
    }
}
