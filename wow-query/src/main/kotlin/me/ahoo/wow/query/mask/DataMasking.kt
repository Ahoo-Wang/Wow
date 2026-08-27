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

package me.ahoo.wow.query.mask

import me.ahoo.wow.api.query.IMaterializedSnapshot
import me.ahoo.wow.api.query.PagedList

interface DataMasking<SOURCE : DataMasking<SOURCE>> : DataMasker {
    fun mask(): SOURCE
}

fun <S : Any> S.tryMask(): S {
    if (this is DataMasking<*>) {
        @Suppress("UNCHECKED_CAST")
        return this.mask() as S
    }
    return this
}

fun <SOURCE : IMaterializedSnapshot<SOURCE, S>, S : Any> SOURCE.tryMask(): SOURCE {
    val state = this.state
    if (state !is DataMasking<*>) {
        return this
    }
    @Suppress("UNCHECKED_CAST")
    val maskedState = state.mask() as S
    return this.withState(maskedState)
}

fun <SOURCE : IMaterializedSnapshot<SOURCE, S>, S : Any> PagedList<SOURCE>.tryMask(): PagedList<SOURCE> {
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
