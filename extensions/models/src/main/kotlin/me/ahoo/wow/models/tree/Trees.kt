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

package me.ahoo.wow.models.tree

/** Builds trees of [Leaf] nodes from flat ones. */
object Trees {
    /** The tree under [root] of these flat nodes, each made a leaf by [toLeaf]. */
    fun <F : Flat, L : Leaf<L>> Iterable<F>.toTree(root: L, toLeaf: (F) -> L): L = buildTree(root, map(toLeaf))

    private fun <L : Leaf<L>> buildTree(parent: L, nodes: List<L>): L {
        val children = nodes.filter { parent.isDirectChild(it) }
        if (children.isEmpty()) {
            return parent
        }
        return parent.withChildren(children = children.map { buildTree(it, nodes) })
    }
}
