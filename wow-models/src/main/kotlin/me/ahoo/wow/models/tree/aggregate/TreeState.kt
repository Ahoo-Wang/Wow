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

package me.ahoo.wow.models.tree.aggregate

import me.ahoo.wow.api.annotation.OnSourcing
import me.ahoo.wow.models.tree.Flat
import me.ahoo.wow.models.tree.ITreeState
import me.ahoo.wow.models.tree.command.Created
import me.ahoo.wow.models.tree.command.Deleted
import me.ahoo.wow.models.tree.command.Moved
import me.ahoo.wow.models.tree.command.Updated
import java.util.*

abstract class TreeState<F : Flat, C : Created, U : Updated, D : Deleted, M : Moved> : ITreeState<F> {
    override val children: SortedSet<F> = sortedSetOf()

    @OnSourcing
    fun onCreated(event: C) {
        children.add(event.toFlat())
    }

    @OnSourcing
    fun onUpdated(event: U) {
        children.removeIf { it.code == event.code }
        children.add(event.toFlat())
    }

    @OnSourcing
    fun onDeleted(event: D) {
        children.removeIf { it.code == event.code }
    }

    @OnSourcing
    fun onMoved(event: M) {
        val flats = buildList {
            event.codes.forEachIndexed { index, code ->
                val flat = children.first { it.code == code }
                children.remove(flat)
                @Suppress("UNCHECKED_CAST")
                add(flat.withSortId(sortId = index) as F)
            }
        }
        children.addAll(flats)
    }
}