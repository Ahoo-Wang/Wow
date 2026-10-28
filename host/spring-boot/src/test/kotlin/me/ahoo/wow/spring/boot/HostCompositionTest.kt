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
package me.ahoo.wow.spring.boot

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.http.caller.CallerResolver
import me.ahoo.wow.spi.config.WowConfigurationException
import me.ahoo.wow.spi.observation.Observation
import me.ahoo.wow.spi.observation.Observer
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The composition rule of the host's hooks: a list hook is every bean in their order, a single seam one bean, several
 * of which fail the start unless one is `@Primary`.
 */
class HostCompositionTest {
    private val seams = ApplicationContextRunner().withBean(SingleSeams::class.java)
    private val first = CallerResolver { null }
    private val second = CallerResolver { null }

    @Test
    fun `several beans of a single seam should refuse the start, naming the seam and each bean`() {
        seams.withBean("firstCallers", CallerResolver::class.java, { first })
            .withBean("secondCallers", CallerResolver::class.java, { second })
            .run { context ->
                val failure = generateSequence(context.startupFailure) { it.cause }
                    .filterIsInstance<WowConfigurationException>().first()
                failure.key.assert().isEqualTo(CallerResolver::class.java.name)
                failure.message.assert().contains("the caller resolver", "firstCallers", "secondCallers", "@Primary")
            }
    }

    @Test
    fun `a Primary bean should be the one way to pick among several`() {
        seams.withBean("firstCallers", CallerResolver::class.java, { first })
            .withBean("secondCallers", CallerResolver::class.java, { second }, { it.isPrimary = true })
            .run { context ->
                context.startupFailure.assert().isNull()
                context.getBean(CallerResolver::class.java).assert().isSameAs(second)
            }
        seams.withBean("firstCallers", CallerResolver::class.java, { first }).run { context ->
            context.startupFailure.assert().isNull()
        }
    }

    /** Two observers of the application's, each recording the commands it saw executed. */
    @Configuration(proxyBeanMethods = false)
    class TwoObservers {
        @Bean
        fun firstObserver(): Recording = Recording()

        @Bean
        fun secondObserver(): Recording = Recording()
    }

    class Recording : Observer {
        val executed = CopyOnWriteArrayList<String>()

        override fun executing(command: Command<*>, commandType: String): Observation {
            executed += commandType
            return Observation.NONE
        }
    }

    @Test
    fun `every observer of the application should see the node's work`() {
        startHost(sources = listOf(HostTestApp::class.java, TwoObservers::class.java)).use { host ->
            host.post("/bank/account/open-account", """{"owner":"olga"}""").status.assert().isEqualTo(200)
            val observers = host.context.getBeansOfType(Recording::class.java).values
            observers.assert().hasSize(2)
            observers.forEach { it.executed.assert().isNotEmpty() }
        }
    }
}
