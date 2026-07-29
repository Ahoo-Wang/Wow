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
 * A one-shot component exclusively owned by [WowRuntime].
 *
 * Construction must be inert. [prepare] establishes subscriptions and resources
 * without opening processing; [start] opens processing only after every runtime
 * component has completed preparation. Both callbacks must return promptly and
 * must not perform unbounded blocking waits.
 *
 * [forceStop] must be prompt, non-blocking, idempotent, thread-safe, and safe
 * before or during preparation and graceful cleanup. The runtime may invoke it
 * concurrently to enforce the shared deadline and replay it after interrupted
 * startup exits so resources created by an in-progress callback are released.
 */
interface RuntimeComponent {
    fun prepare(runtimeContext: RuntimeContext)

    fun start()

    fun stopGracefully(): Mono<Void>

    fun forceStop()
}
