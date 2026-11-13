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

package me.ahoo.wow.spi.store

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The [StoreCallMarks] a thread holds while it runs a clocked store call's blocking section, for a driver
 * listener that times the call's commands ([StoreCallMarks.commanded]) and has nothing but its thread to find the call
 * by. The benchmark server's listener reads it ([held]); nothing else does. The store [hold]s the marks as the section
 * starts and [release]s them in its `finally`, so the section ends holding none, returned or thrown; both do nothing for
 * a call without marks, which holds none.
 *
 * It relies on the driver running a command and reporting its events on the thread that called it, as MongoDB's
 * synchronous driver does. Were a driver to report them on a thread of its own, its listener would find nothing held
 * there (no thread but a store call's holds marks, and only during its section), so it would add nothing, never
 * another call's marks.
 */
@InternalWowApi
object HeldStoreCallMarks {
    private val current = ThreadLocal<StoreCallMarks?>()

    /** The marks this thread holds now: those of the clocked call whose section it runs, else none. */
    fun held(): StoreCallMarks? = current.get()

    /** Holds [marks], when the call is clocked, as its blocking section starts on this thread. */
    fun hold(marks: StoreCallMarks?) {
        if (marks != null) current.set(marks)
    }

    /** Holds no marks any more, the section of a call that [hold] [marks] ended: in its `finally`. */
    fun release(marks: StoreCallMarks?) {
        if (marks != null) current.remove()
    }
}
