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

package me.ahoo.wow.spi.log

import me.ahoo.wow.api.annotation.InternalWowApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The instants one call to [EventLog.appendAll] passes, in [System.nanoTime], a diagnostic of where the call's time
 * goes. The node's append queue puts one in the call's coroutine context only while something reads its counts, and
 * marks [taken] (when it makes it), [returned] and [done]; a log marks what only it sees, from [entered] to
 * [released], and a log that marks nothing leaves them at zero. Nothing is marked without one: a log looks it up once
 * a call and finds none.
 *
 * Each mark is written by the one coroutine the call runs in at that point, in order; the call's resumptions order
 * them, so the queue reads every mark the log wrote once the call returns.
 */
@InternalWowApi
class AppendCallMarks : AbstractCoroutineContextElement(Key) {
    /** The group taken from the queue, the call about to be made. */
    val taken: Long = System.nanoTime()

    /** The log's call entered, on the caller's thread. */
    var entered: Long = 0
        private set

    /** The call on the thread that does the log's blocking work, after the hop to it. */
    var onThread: Long = 0
        private set

    /** The store's connection acquired: the first, when the call takes several. */
    var acquired: Long = 0
        private set

    /** The first statement sent, its parameters bound. */
    var sent: Long = 0
        private set

    /** The last statement's result read. */
    var read: Long = 0
        private set

    /** The connection given back, the log's work done, still on its thread. */
    var released: Long = 0
        private set

    /** The call returned to the queue, its outcomes in hand. */
    var returned: Long = 0
        private set

    /** The outcomes handed to their callers, the queue's committer free for its next group. */
    var done: Long = 0
        private set

    /** Whether the log marked every instant it sees, so each step between [taken] and [done] is known. */
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

    @InternalWowApi
    companion object Key : CoroutineContext.Key<AppendCallMarks>
}
