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
import me.ahoo.wow.models.tree.CopySortIdFlat
import me.ahoo.wow.models.tree.Flat
import me.ahoo.wow.models.tree.ITreeState
import me.ahoo.wow.models.tree.command.Created
import me.ahoo.wow.models.tree.command.Deleted
import me.ahoo.wow.models.tree.command.Moved
import me.ahoo.wow.models.tree.command.Updated
import java.util.SortedSet

/**
 * The state of a [Tree]: its nodes as flat [F]s. Subclass it with the concrete events ([C], [U], [D], [M]) and
 * [toFlat]; the compiler binds the sourcing functions declared here with the subclass's type arguments:
 *
 * ```kotlin
 * class CategoryState(override val id: String) :
 *     TreeState<FlatCategory, CategoryCreated, CategoryUpdated, CategoryDeleted, CategoryMoved>() {
 *     override fun Flat.toFlat(): FlatCategory = FlatCategory(name, code, sortId)
 * }
 * ```
 */
abstract class TreeState<F : CopySortIdFlat<F>, C : Created, U : Updated, D : Deleted, M : Moved> : ITreeState<F> {
    override val children: SortedSet<F> = sortedSetOf()

    /** This node as the state keeps it. */
    protected abstract fun Flat.toFlat(): F

    @OnSourcing
    open fun onCreated(event: C) {
        children.add(event.toFlat())
    }

    @OnSourcing
    open fun onUpdated(event: U) {
        children.removeIf { it.code == event.code }
        children.add(event.toFlat())
    }

    @OnSourcing
    open fun onDeleted(event: D) {
        children.removeIf { it.code == event.code }
    }

    @OnSourcing
    open fun onMoved(event: M) {
        val moved = event.codes.mapIndexed { index, code ->
            val flat = children.first { it.code == code }
            children.remove(flat)
            flat.withSortId(sortId = index)
        }
        children.addAll(moved)
    }
}
