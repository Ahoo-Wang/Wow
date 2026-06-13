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

package me.ahoo.wow.command.wait

import java.util.concurrent.ConcurrentHashMap

interface WaitCoordinator {
    fun createLast(plan: WaitPlan): WaitLastHandle

    fun createStream(plan: WaitPlan): WaitStreamHandle

    fun signal(signal: WaitSignal): Boolean

    operator fun contains(waitCommandId: String): Boolean
}

class DefaultWaitCoordinator : WaitCoordinator {
    private val reducer: WaitSignalReducer
    private val streamQueueLinkSize: Int
    private val handles = ConcurrentHashMap<String, WaitHandle>()

    constructor(streamQueueLinkSize: Int = DEFAULT_WAIT_STREAM_QUEUE_LINK_SIZE) {
        reducer = DefaultWaitSignalReducer()
        this.streamQueueLinkSize = streamQueueLinkSize
    }

    internal constructor(
        reducer: WaitSignalReducer,
        streamQueueLinkSize: Int = DEFAULT_WAIT_STREAM_QUEUE_LINK_SIZE,
    ) {
        this.reducer = reducer
        this.streamQueueLinkSize = streamQueueLinkSize
    }

    override fun createLast(plan: WaitPlan): WaitLastHandle {
        lateinit var handle: WaitLastHandle
        handle = DefaultWaitLastHandle(plan, reducer) {
            unregister(plan.waitCommandId, handle)
        }
        register(plan.waitCommandId, handle)
        return handle
    }

    override fun createStream(plan: WaitPlan): WaitStreamHandle {
        lateinit var handle: WaitStreamHandle
        handle = DefaultWaitStreamHandle(
            plan = plan,
            reducer = reducer,
            onTerminate = {
                unregister(plan.waitCommandId, handle)
            },
            queueLinkSize = streamQueueLinkSize,
        )
        register(plan.waitCommandId, handle)
        return handle
    }

    private fun register(waitCommandId: String, handle: WaitHandle) {
        val previous = handles.putIfAbsent(waitCommandId, handle)
        require(previous == null) {
            "Wait handle already registered for waitCommandId[$waitCommandId]."
        }
    }

    override fun signal(signal: WaitSignal): Boolean {
        val handle = handles[signal.waitCommandId] ?: return false
        return handle.next(signal)
    }

    private fun unregister(waitCommandId: String, handle: WaitHandle) {
        handles.remove(waitCommandId, handle)
    }

    override fun contains(waitCommandId: String): Boolean =
        handles.containsKey(waitCommandId)
}
