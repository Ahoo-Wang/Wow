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

package me.ahoo.wow.client.java

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.Spliterator
import java.util.Spliterators
import java.util.concurrent.CompletableFuture
import java.util.stream.Stream
import java.util.stream.StreamSupport

/**
 * How the Java facade runs the suspending API: a blocking call on the caller's thread, which
 * must not be a non-blocking event loop (a Netty or WebFlux thread); an asynchronous one on [Dispatchers.Default],
 * whose [CompletableFuture] completes with the call's result or its exception, the same [me.ahoo.wow.api.client.
 * CommandRejectedException] or [me.ahoo.wow.api.problem.WowProblemException] the blocking call throws.
 */
internal object JavaFlows {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

    fun <T> async(block: suspend () -> T): CompletableFuture<T> = scope.future { block() }

    /**
     * [flow] as a sequential [Stream] read on the caller's thread: each element is asked for when the stream needs
     * it; closing the stream (a try-with-resources) cancels the flow. A failure of the flow is thrown from the
     * stream's terminal operation.
     */
    fun <T : Any> stream(flow: Flow<T>): Stream<T> {
        val collecting = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val elements = flow.buffer(STREAM_BUFFER).produceIn(collecting)
        val iterator = object : Iterator<T> {
            private var next: ChannelResult<T>? = null

            private fun peek(): ChannelResult<T> = next ?: runBlocking { elements.receiveCatching() }.also { next = it }

            override fun hasNext(): Boolean {
                val result = peek()
                result.exceptionOrNull()?.let { throw it }
                return result.isSuccess
            }

            override fun next(): T {
                if (!hasNext()) throw NoSuchElementException()
                return next!!.getOrThrow().also { next = null }
            }
        }
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
            .onClose { collecting.cancel() }
    }

    private const val STREAM_BUFFER = 16
}
