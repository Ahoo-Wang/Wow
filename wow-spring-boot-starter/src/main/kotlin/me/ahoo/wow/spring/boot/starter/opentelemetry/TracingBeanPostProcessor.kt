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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.opentelemetry.Tracing.tracing
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered

class TracingBeanPostProcessor : BeanPostProcessor, Ordered {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        val tracingBean = bean.tracing()
        if (tracingBean !== bean) {
            log.info {
                "Tracing bean [$beanName] [${bean.javaClass.name}] -> [${tracingBean.javaClass.name}]"
            }
        }
        return tracingBean
    }

    override fun getOrder(): Int {
        return Ordered.HIGHEST_PRECEDENCE
    }
}
