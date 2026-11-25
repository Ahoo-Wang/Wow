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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.id.Ids

/**
 * The ids one saga handling draws ([me.ahoo.wow.api.subscribe.SagaContext.ids]) from [ids], remembered so that the
 * handling can refuse a command whose target is named by one of them ([drew]): handling the commit again draws other
 * ids, so such a command is another request to another aggregate each time.
 *
 * It holds nothing until the handler draws an id: most handlings draw none, and their set is never allocated.
 */
internal class DrawnIds(private val ids: Ids) : Ids {
    private var drawn: MutableSet<String>? = null

    override fun next(): String = remembered(ids.next())

    override fun next(type: AggregateType): String = remembered(ids.next(type))

    /** Whether this handling drew [id]. */
    fun drew(id: String): Boolean = synchronized(this) { drawn?.contains(id) == true }

    private fun remembered(id: String): String = id.also {
        synchronized(this) { (drawn ?: HashSet<String>().also { set -> drawn = set }).add(it) }
    }
}
