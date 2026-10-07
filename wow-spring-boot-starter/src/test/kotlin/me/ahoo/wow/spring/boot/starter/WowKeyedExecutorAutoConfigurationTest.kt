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

package me.ahoo.wow.spring.boot.starter

import me.ahoo.test.asserts.assert
import me.ahoo.wow.execution.KeyedExecutor
import me.ahoo.wow.runtime.WowRuntime
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/** The runtime's [KeyedExecutor] as a context bean: who builds it, who owns it, and that the context closes it. */
internal class WowKeyedExecutorAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun `the runtime's keyed executor is a context bean closed with the context`() {
        lateinit var keyedExecutor: KeyedExecutor
        contextRunner
            .enableWow()
            .run { context ->
                keyedExecutor = context.getBean(KeyedExecutor::class.java)
                context.getBean(WowRuntime::class.java).keyedExecutor.assert().isSameAs(keyedExecutor)
            }
        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `an application keyed executor replaces the starter's and is the runtime's`() {
        lateinit var keyedExecutor: KeyedExecutor
        contextRunner
            .withUserConfiguration(CustomKeyedExecutorConfiguration::class.java)
            .enableWow()
            .run { context ->
                context.assert().hasSingleBean(KeyedExecutor::class.java)
                    .doesNotHaveBean(WowAutoConfiguration.WOW_KEYED_EXECUTOR_BEAN_NAME)
                keyedExecutor = context.getBean(KeyedExecutor::class.java)
                keyedExecutor.name.assert().isEqualTo("custom-dispatch")
                context.getBean(WowRuntime::class.java).keyedExecutor.assert().isSameAs(keyedExecutor)
            }
        keyedExecutor.isDisposed.assert().isTrue()
    }

    @Test
    fun `a child context builds its own keyed executor and leaves the parent's open`() {
        contextRunner
            .withUserConfiguration(CustomKeyedExecutorConfiguration::class.java)
            .enableWow()
            .run { parent ->
                val parentExecutor = parent.getBean(KeyedExecutor::class.java)
                lateinit var childExecutor: KeyedExecutor
                contextRunner
                    .withParent(parent)
                    .enableWow()
                    .run { child ->
                        child.startupFailure.assert().isNull()
                        childExecutor = child.getBean(WowRuntime::class.java).keyedExecutor
                        childExecutor.assert().isNotSameAs(parentExecutor)
                    }
                childExecutor.isDisposed.assert().isTrue()
                parentExecutor.isDisposed.assert().isFalse()
            }
    }

    @Test
    fun `a context refresh failure closes the keyed executor and leaks no dispatch thread`() {
        DispatchingThenFailingConfiguration.reset()
        contextRunner
            .enableWow()
            .withUserConfiguration(DispatchingThenFailingConfiguration::class.java)
            .run { context ->
                context.startupFailure.assert().isNotNull()
            }
        val keyedExecutor = checkNotNull(DispatchingThenFailingConfiguration.keyedExecutor.get())
        val worker = checkNotNull(DispatchingThenFailingConfiguration.worker.get())
        keyedExecutor.isDisposed.assert().isTrue()
        worker.name.assert().startsWith(KeyedExecutor.DEFAULT_NAME)
        worker.join(Duration.ofSeconds(5).toMillis())
        worker.isAlive.assert().isFalse()
    }

    @Configuration(proxyBeanMethods = false)
    private class CustomKeyedExecutorConfiguration {
        @Bean
        fun customKeyedExecutor(): KeyedExecutor = KeyedExecutor(workers = 1, name = "custom-dispatch")
    }

    /** Starts a dispatch worker during refresh, then fails the refresh. */
    @Configuration(proxyBeanMethods = false)
    class DispatchingThenFailingConfiguration {
        @Bean
        fun dispatchingThenFailing(keyedExecutor: KeyedExecutor): Any {
            Companion.keyedExecutor.set(keyedExecutor)
            val ran = CountDownLatch(1)
            keyedExecutor.coroutineDispatcher.dispatch(EmptyCoroutineContext) {
                worker.set(Thread.currentThread())
                ran.countDown()
            }
            check(ran.await(5, TimeUnit.SECONDS)) { "The dispatch worker did not run." }
            throw IllegalStateException("refresh fails after the first dispatch")
        }

        companion object {
            val keyedExecutor = AtomicReference<KeyedExecutor>()
            val worker = AtomicReference<Thread>()

            fun reset() {
                keyedExecutor.set(null)
                worker.set(null)
            }
        }
    }
}
