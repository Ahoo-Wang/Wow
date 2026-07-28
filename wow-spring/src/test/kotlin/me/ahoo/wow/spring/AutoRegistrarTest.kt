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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationContext
import org.springframework.context.support.GenericApplicationContext
import java.util.concurrent.atomic.AtomicInteger

class AutoRegistrarTest {

    @Test
    fun `start is idempotent while running and restart fails before registration`() {
        val applicationContext = GenericApplicationContext()
        applicationContext.beanFactory.registerSingleton(
            "annotatedComponent",
            AnnotatedComponent(),
        )
        applicationContext.refresh()
        try {
            val registrar = RecordingAutoRegistrar(applicationContext)

            registrar.start()
            registrar.start()

            registrar.registerCount.get().assert().isEqualTo(1)
            registrar.isRunning.assert().isTrue()
            registrar.isPauseable.assert().isFalse()
            registrar.phase.assert().isEqualTo(AUTO_REGISTRAR_PHASE)

            registrar.stop()
            val error = assertThrows<IllegalStateException>(registrar::start)

            error.message.assert().contains("Create a new ApplicationContext")
            registrar.registerCount.get().assert().isEqualTo(1)
            registrar.isRunning.assert().isFalse()
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
    ) : AutoRegistrar<TestComponent>(TestComponent::class.java, applicationContext) {
        val registerCount = AtomicInteger()

        override fun register(component: Any) {
            registerCount.incrementAndGet()
        }
    }
}
