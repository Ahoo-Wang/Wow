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

package me.ahoo.wow.api.id

import me.ahoo.wow.api.aggregate.AggregateType

/**
 * The ids domain code takes (decision D171), from a decide function ([me.ahoo.wow.api.decide.DecideScope.ids]) or a
 * saga's handler ([me.ahoo.wow.api.subscribe.SagaContext.ids]) instead of a static generator. A decide function's are
 * the node's generators' (UUIDv7 by default, CosId with wow-cosid), fixed ones in the test DSL; a saga's are
 * name-based UUIDs of its handling, the same every time the same commit is handled.
 */
interface Ids {
    /** A new global id, such as the id of a line, a payment attempt or a correlation key. */
    fun next(): String

    /** A new id for an aggregate of [type], as admission gives a creating command that names none. */
    fun next(type: AggregateType): String
}
