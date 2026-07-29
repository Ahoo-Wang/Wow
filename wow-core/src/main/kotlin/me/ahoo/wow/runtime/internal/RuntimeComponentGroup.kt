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

package me.ahoo.wow.runtime.internal

import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import reactor.core.Exceptions
import reactor.core.publisher.Mono

internal class RuntimeComponentGroup(
    private val components: List<RuntimeComponent>,
) {
    private val prepared = mutableListOf<RuntimeComponent>()

    @Suppress("TooGenericExceptionCaught")
    fun prepare(runtimeContext: RuntimeContext, afterEach: () -> Unit = {}) {
        components.forEach { component ->
            try {
                component.prepare(runtimeContext)
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
                try {
                    component.forceStop()
                } catch (forceStopError: Throwable) {
                    Exceptions.throwIfFatal(forceStopError)
                    if (forceStopError !== error) {
                        error.addSuppressed(forceStopError)
                    }
                }
                throw error
            }
            prepared += component
            afterEach()
        }
    }

    fun start(afterEach: () -> Unit = {}) {
        prepared.forEach { component ->
            component.start()
            afterEach()
        }
    }

    fun stopGracefully(): Mono<Void> {
        val failures = FailureAccumulator()
        return prepared.asReversed().fold(Mono.empty<Void>()) { chain, component ->
            chain.then(
                Mono.defer(component::stopGracefully)
                    .onErrorResume { error ->
                        Exceptions.throwIfFatal(error)
                        failures.record(error)
                        Mono.empty<Void>()
                    }
            )
        }.then(
            Mono.defer {
                failures.current()?.let { Mono.error<Void>(it) } ?: Mono.empty()
            }
        )
    }

    @Suppress("TooGenericExceptionCaught")
    fun forceStop(): Throwable? {
        val failures = FailureAccumulator()
        components.asReversed().forEach { component ->
            try {
                component.forceStop()
            } catch (error: Throwable) {
                Exceptions.throwIfFatal(error)
                failures.record(error)
            }
        }
        return failures.current()
    }
}
