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

package me.ahoo.wow.runtime

/**
 * A single operation admitted by [RuntimeContext].
 *
 * Closing the lease is idempotent and removes the operation from global runtime
 * activity. Implementations should acquire the lease before accepting work and
 * close it only after the complete asynchronous operation chain terminates.
 */
fun interface RuntimeActivity : AutoCloseable {
    override fun close()
}

/**
 * Public collaboration boundary for components managed by a Wow runtime.
 *
 * Components use this context to track complete asynchronous operations,
 * register their intake-close barrier, and report terminal failures. Runtime
 * orchestration operations such as quiescing and force-close remain internal.
 */
interface RuntimeContext {
    val activeOperationCount: Long

    val isQuiescing: Boolean

    /**
     * Whether this runtime has permanently stopped admitting new operations.
     */
    val isAdmissionClosed: Boolean

    /**
     * Reports a terminal component failure to the owning runtime.
     */
    fun reportFailure(error: Throwable)

    /**
     * Registers, during component preparation, an idempotent action that closes
     * component intake at the global quiet boundary.
     *
     * The action is dispatched asynchronously. A registration that races with
     * admission closure is also dispatched. Hard force-stop may cancel queued or
     * running actions, so [RuntimeComponent.forceStop] remains the authoritative
     * synchronous intake-closure path.
     */
    fun onAdmissionClose(action: () -> Unit)

    /**
     * Attempts to admit one complete asynchronous operation.
     *
     * @return an idempotent activity lease, or `null` after runtime admission closes
     */
    fun tryAcquire(): RuntimeActivity?
}
