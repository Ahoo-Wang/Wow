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

package me.ahoo.wow.api.read

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The name of a dataset: the bounded context that publishes it and its name there, `context.dataset`
 * on the wire. A dataset is a read model of its own, not an aggregate, so it has a name type of its own.
 */
@ExperimentalWowApi
class DatasetName @InternalWowApi constructor(val context: String, val name: String) {
    init {
        require(context.isNotBlank()) { "A dataset name needs a context." }
        require(name.isNotBlank()) { "A dataset name needs a name." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is DatasetName && context == other.context && name == other.name

    override fun hashCode(): Int = 31 * context.hashCode() + name.hashCode()

    override fun toString(): String = "$context.$name"

    companion object {
        /** The dataset [name] of [context]. */
        @JvmStatic
        fun of(context: String, name: String): DatasetName = DatasetName(context, name)
    }
}
