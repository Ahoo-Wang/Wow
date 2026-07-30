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
import me.ahoo.wow.runtime.RuntimeComponent
import me.ahoo.wow.runtime.RuntimeContext
import me.ahoo.wow.runtime.WowRuntime
import org.aopalliance.intercept.MethodInterceptor
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import reactor.core.publisher.Mono
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WowRuntimeProxyTest {

    @Test
    fun `runtime invokes the exposed proxy exactly once`() {
        val target = RecordingRuntimeComponent()
        val advisedCalls = ConcurrentHashMap<String, AtomicInteger>()
        val proxy = ProxyFactory(target).apply {
            setInterfaces(RuntimeComponent::class.java)
            addAdvice(
                MethodInterceptor { invocation ->
                    advisedCalls.computeIfAbsent(invocation.method.name) {
                        AtomicInteger()
                    }.incrementAndGet()
                    invocation.proceed()
                },
            )
        }.proxy as RuntimeComponent

        ApplicationContextRunner()
            .enableWow()
            .withBean("proxiedRuntimeComponent", RuntimeComponent::class.java, { proxy })
            .run { context ->
                context.getBean(WowRuntime::class.java).components.single()
                    .assert()
                    .isSameAs(proxy)
                target.prepareCount.get().assert().isOne()
                target.startCount.get().assert().isOne()
                advisedCalls["prepare"]?.get().assert().isEqualTo(1)
                advisedCalls["start"]?.get().assert().isEqualTo(1)
            }

        target.stopCount.get().assert().isOne()
        advisedCalls["stopGracefully"]?.get().assert().isEqualTo(1)
        advisedCalls["forceStop"].assert().isNull()
    }

    private class RecordingRuntimeComponent : RuntimeComponent {
        val prepareCount = AtomicInteger()
        val startCount = AtomicInteger()
        val stopCount = AtomicInteger()

        override fun prepare(runtimeContext: RuntimeContext): Mono<Void> =
            Mono.fromRunnable(prepareCount::incrementAndGet)

        override fun start() {
            startCount.incrementAndGet()
        }

        override fun stopGracefully(): Mono<Void> =
            Mono.fromRunnable(stopCount::incrementAndGet)

        override fun forceStop() = Unit
    }
}
