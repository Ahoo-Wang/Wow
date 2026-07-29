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

package me.ahoo.wow.spring

import me.ahoo.test.asserts.assert
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.WowRuntime
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.context.support.GenericApplicationContext
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

class AutoRegistrarTest {

    @Test
    fun `registers annotated components after singleton initialization`() {
        val applicationContext = GenericApplicationContext()
        applicationContext.registerBean(
            "annotatedComponent",
            AnnotatedComponent::class.java,
            Supplier(::AnnotatedComponent),
        )
        val registrar = RecordingAutoRegistrar(applicationContext)
        applicationContext.registerBean(
            "autoRegistrar",
            RecordingAutoRegistrar::class.java,
            Supplier { registrar },
        )
        try {
            applicationContext.refresh()

            registrar.registerCount.get().assert().isEqualTo(1)
        } finally {
            applicationContext.close()
        }
    }

    @Test
    fun `registration completes before runtime readiness`() {
        val calls = CopyOnWriteArrayList<String>()
        val applicationContext = GenericApplicationContext()
        applicationContext.registerBean(
            "annotatedComponent",
            AnnotatedComponent::class.java,
            Supplier(::AnnotatedComponent),
        )
        val registrar = RecordingAutoRegistrar(applicationContext) {
            calls += "register"
        }
        applicationContext.registerBean(
            "autoRegistrar",
            RecordingAutoRegistrar::class.java,
            Supplier { registrar },
        )
        val runtimeComponent = object : RuntimeComponent {
            override fun prepare(runtimeContext: RuntimeContext) {
                calls += "prepare"
            }

            override fun start() {
                calls += "start"
            }

            override fun stopGracefully(): Mono<Void> = Mono.empty()

            override fun forceStop() = Unit
        }
        val runtime = WowRuntime(
            components = listOf(runtimeComponent),
            shutdownTimeout = Duration.ofSeconds(1),
            shutdownQuietPeriod = Duration.ZERO,
        )
        applicationContext.registerBean(
            "wowRuntimeLifecycle",
            WowRuntimeLifecycle::class.java,
            Supplier { WowRuntimeLifecycle(runtime) },
        )

        try {
            applicationContext.refresh()

            calls.assert().containsExactly("register", "prepare", "start")
        } finally {
            applicationContext.close()
        }
    }

    @Target(AnnotationTarget.CLASS)
    @Retention(AnnotationRetention.RUNTIME)
    private annotation class TestComponent

    @TestComponent
    private class AnnotatedComponent

    private class RecordingAutoRegistrar(
        applicationContext: ApplicationContext,
        private val onRegister: () -> Unit = {},
    ) : AutoRegistrar<TestComponent>(TestComponent::class.java, applicationContext) {
        val registerCount = AtomicInteger()

        override fun register(component: Any) {
            registerCount.incrementAndGet()
            onRegister()
        }
    }
}
