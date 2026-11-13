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
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The instants one call to a store passes, in [System.nanoTime], a diagnostic of where the call's time goes: an event
 * log's `appendAll` or a snapshot store's `saveAll`. The node's caller (the append queue, the snapshot writes) puts one
 * in the call's coroutine context only while something reads its counts, and marks [taken] (when it makes it),
 * [returned] and [done]. A store marks what only it sees, from [entered] to [released], and adds its driver's own
 * [command] time when its driver reports it; a store that marks nothing leaves them at zero. Nothing is marked without
 * one: a store looks it up once a call and finds none.
 *
 * Each mark is written by the one coroutine the call runs in at that point, in order; the call's resumptions order
 * them, so the caller reads every mark the store wrote once the call returns.
 */
@InternalWowApi
class StoreCallMarks : AbstractCoroutineContextElement(Key) {
    /** The batch taken by the caller, the call about to be made. */
    val taken: Long = System.nanoTime()

    /** The store's call entered, on the caller's thread. */
    var entered: Long = 0
        private set

    /** The call on the thread that does the store's blocking work, after the hop to it. */
    var onThread: Long = 0
        private set

    /** The store's connection acquired: the first, when the call takes several. */
    var acquired: Long = 0
        private set

    /** The first command or statement handed to the driver, its parameters or documents built. */
    var sent: Long = 0
        private set

    /** The last command's or statement's result read. */
    var read: Long = 0
        private set

    /** The connection given back, the store's work done, still on its thread. */
    var released: Long = 0
        private set

    /** The call returned to the caller, its outcomes in hand. */
    var returned: Long = 0
        private set

    /** The outcomes handed to their callers, the caller's committer free for its next batch. */
    var done: Long = 0
        private set

    /**
     * The driver's own time of the call's commands from [sent] to [read], in nanoseconds: from each command's message
     * sent to its reply, as the driver reports it ([commands] of them). A command before [sent] (a lookup while the
     * store builds the batch) is [sent]'s, not counted here. Zero where the driver reports none (no listener, or a store
     * whose driver has none).
     */
    var command: Long = 0
        private set

    /** The commands the driver reported ([command]). */
    var commands: Int = 0
        private set

    /** Whether the store marked every instant it sees, so each step between [taken] and [done] is known. */
    val marked: Boolean
        get() = listOf(entered, onThread, acquired, sent, read, released).none { it == 0L }

    fun entered() {
        entered = System.nanoTime()
    }

    fun onThread() {
        onThread = System.nanoTime()
    }

    fun acquired() {
        if (acquired == 0L) acquired = System.nanoTime()
    }

    fun sent() {
        if (sent == 0L) sent = System.nanoTime()
    }

    fun read() {
        read = System.nanoTime()
    }

    fun released() {
        released = System.nanoTime()
    }

    fun returned() {
        returned = System.nanoTime()
    }

    fun done() {
        done = System.nanoTime()
    }

    /** One of the call's commands took [nanos] by its driver's account: counted between [sent] and [read]. */
    fun commanded(nanos: Long) {
        if (sent != 0L && read == 0L) {
            command += nanos
            commands++
        }
    }

    @InternalWowApi
    companion object Key : CoroutineContext.Key<StoreCallMarks>
}
