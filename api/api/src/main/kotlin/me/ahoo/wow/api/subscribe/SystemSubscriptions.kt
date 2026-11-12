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

package me.ahoo.wow.api.subscribe

/**
 * The names of Wow's built-in subscriptions a wait or an operator names, the ones a result's
 * [FunctionKind.SYSTEM][me.ahoo.wow.api.result.FunctionKind.SYSTEM] is of among them. Every built-in subscription's
 * name starts with `wow.`, a prefix the compiler reserves, so no application subscription takes one.
 */
object SystemSubscriptions {
    /**
     * The built-in subscription that writes snapshots, a wait's `snapshot` hop ([me.ahoo.wow.api.result.Hop.snapshot]).
     */
    const val SNAPSHOT: String = "wow.snapshot"

    /**
     * A wait's `queryable` hop ([me.ahoo.wow.api.result.Hop.queryable]): the commit is in the store the aggregate type's
     * snapshot queries read. It is answered by the snapshot subscription that writes that store: the one of the type's
     * copy where its queries read another store, else the one of its own store, as [SNAPSHOT] is. No subscription
     * runs under this name.
     */
    const val QUERYABLE: String = "wow.queryable"

    /** The built-in subscription that confirms reservations. */
    const val RESERVATION: String = "wow.reservation"

    /** The built-in subscription that writes the timers of scheduled commands. */
    const val TIMERS: String = "wow.timers"
}
