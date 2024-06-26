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

package me.ahoo.wow.api.query

interface DataMasking<SOURCE : DataMasking<SOURCE>> {
    fun mask(): SOURCE
}

fun <S : Any> MaterializedSnapshot<S>.tryMask(): MaterializedSnapshot<S> {
    val state = this.state
    if (state is DataMasking<*>) {
        @Suppress("UNCHECKED_CAST")
        return this.copy(state = state.mask() as S)
    }
    return this
}

fun <T : Any> PagedList<MaterializedSnapshot<T>>.tryMask(): PagedList<MaterializedSnapshot<T>> {
    if (list.isEmpty()) {
        return this
    }

    val firstState = list.first().state
    if (firstState !is DataMasking<*>) {
        return this
    }

    val maskedList = list.map {
        it.tryMask()
    }

    return PagedList(
        total = total,
        list = maskedList
    )
}
