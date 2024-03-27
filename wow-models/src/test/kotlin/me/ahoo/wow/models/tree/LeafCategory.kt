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

data class LeafCategory(
    override val name: String,
    override val code: String,
    override val sortId: Int,
    override val children: List<Leaf>
) : Leaf {

    override fun <L : Leaf> withChildren(children: List<Leaf>): L {
        @Suppress("UNCHECKED_CAST")
        return copy(children = children) as L
    }

    companion object {
        val ROOT = LeafCategory(ROOT_CODE, ROOT_CODE, 0, emptyList())
    }
}
