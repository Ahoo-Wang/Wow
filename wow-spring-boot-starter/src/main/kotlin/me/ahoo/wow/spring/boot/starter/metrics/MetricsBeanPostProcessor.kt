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

package me.ahoo.wow.spring.boot.starter.metrics

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.metrics.Metrics.metrizable
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered

class MetricsBeanPostProcessor : BeanPostProcessor, Ordered {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        val metatableBean = bean.metrizable()
        if (metatableBean !== bean) {
            log.info {
                "Magnetizable bean [$beanName] [${bean.javaClass.name}] -> [${metatableBean.javaClass.name}]"
            }
        }
        return metatableBean
    }

    override fun getOrder(): Int {
        return Ordered.LOWEST_PRECEDENCE
    }
}
