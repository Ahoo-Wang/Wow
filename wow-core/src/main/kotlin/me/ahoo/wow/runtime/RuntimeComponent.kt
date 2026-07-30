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

import reactor.core.publisher.Mono

/**
 * Complete lifecycle contract owned by [WowRuntime].
 *
 * Construction must be inert: acquire resources only from [prepare] or [start].
 * [forceStop] must nevertheless be safe before preparation so a
 * failed container refresh can release any accidentally pre-existing resources.
 *
 * [forceStop] may be invoked again when force-stop overlaps a lifecycle action.
 * For [prepare] and [stopGracefully], compensation follows publisher
 * termination or the return of upstream cancellation. For [start] and
 * [quiesce], it follows method return. If force-stop wins before a publisher is
 * subscribed, the runtime does not subscribe it.
 *
 * This contract deliberately does not extend [AutoCloseable]. Container
 * integrations must delegate lifecycle exclusively to [WowRuntime] instead of
 * inferring an independent `close()` owner for every component.
 */
interface RuntimeComponent {
    /**
     * Prepares this component without opening message processing.
     *
     * The returned publisher completes only after the component can retain
     * admitted work without loss, while processing remains closed until
     * [start]. It must terminate promptly when [forceStop] cancels preparation.
     */
    fun prepare(runtimeContext: RuntimeContext): Mono<Void>

    fun start()

    /**
     * Stops admitting new work after the runtime has atomically closed global
     * admission.
     *
     * This method must be prompt, non-blocking, and idempotent. Components
     * without an intake boundary may implement it as a no-op.
     */
    fun quiesce() = Unit

    fun stopGracefully(): Mono<Void>

    /**
     * Releases resources promptly without blocking, and remains safe before
     * [prepare] and across repeated or overlapping calls.
     */
    fun forceStop()
}
