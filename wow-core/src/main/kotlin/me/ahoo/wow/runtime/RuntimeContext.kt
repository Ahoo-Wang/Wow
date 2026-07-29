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
 * An idempotent lease for one complete asynchronous operation.
 */
fun interface RuntimeActivity : AutoCloseable {
    override fun close()
}

/**
 * Collaboration boundary shared by all components in one [WowRuntime].
 */
interface RuntimeContext {
    val activeOperationCount: Long

    val isQuiescing: Boolean

    val isAdmissionClosed: Boolean

    /**
     * Acquires admission for a complete asynchronous operation.
     *
     * The returned lease must be closed only after the operation and every
     * downstream action caused by it have terminated.
     */
    fun tryAcquire(): RuntimeActivity?

    /**
     * Registers a prompt, idempotent action that closes this component's intake.
     */
    fun onAdmissionClose(action: () -> Unit)

    /**
     * Reports a fatal component failure to the complete runtime.
     *
     * Reporting a failure closes global admission immediately. Operations that
     * were already admitted are allowed to drain before component cleanup starts.
     */
    fun reportFailure(error: Throwable)
}
