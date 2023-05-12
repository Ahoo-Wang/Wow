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

package me.ahoo.wow.spring.boot.starter.opentelemetry

import me.ahoo.wow.command.CommandBus
import me.ahoo.wow.command.CommandGateway
import me.ahoo.wow.command.InMemoryCommandBus
import me.ahoo.wow.command.LocalFirstCommandBus
import me.ahoo.wow.event.DomainEventBus
import me.ahoo.wow.infra.Decorator.Companion.getDelegate
import me.ahoo.wow.messaging.DistributedMessageBus
import me.ahoo.wow.metrics.Metrics.metrizable
import me.ahoo.wow.opentelemetry.messaging.Tracing.tracing
import me.ahoo.wow.opentelemetry.messaging.TracingMessageBus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered

class TracingBeanPostProcessor(private val localFirstEnabled: Boolean) : BeanPostProcessor, Ordered {
    companion object {
        private val log = LoggerFactory.getLogger(TracingBeanPostProcessor::class.java)
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean is TracingMessageBus<*>) {
            return bean
        }
        val tracingBean =
            when (bean) {
                is CommandGateway -> bean
                is DomainEventBus -> {
                    bean.tracing()
                }

                is CommandBus -> {
                    val tracingCommandBus = bean.tracing()
                    if (localFirstEnabled &&
                        bean.getDelegate() is DistributedMessageBus
                    ) {
                        LocalFirstCommandBus(
                            distributedCommandBus = tracingCommandBus.metrizable(),
                            localCommandBus = InMemoryCommandBus().tracing().metrizable()
                        )
                    } else {
                        tracingCommandBus
                    }
                }

                else -> bean
            }

        if (tracingBean !== bean && log.isInfoEnabled) {
            log.info("Tracing bean [{}] [{}] -> [{}]", beanName, bean.javaClass.name, tracingBean.javaClass.name)
        }
        return tracingBean
    }

    override fun getOrder(): Int {
        return Ordered.HIGHEST_PRECEDENCE
    }
}
