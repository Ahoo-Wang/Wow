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
import me.ahoo.wow.eventsourcing.EventStore
import me.ahoo.wow.eventsourcing.snapshot.SnapshotStore
import me.ahoo.wow.metrics.MetricDecoratorFactory
import me.ahoo.wow.metrics.WowMetrics
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.EventStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.core.Ordered

class MetricsBeanPostProcessor(
    metrics: WowMetrics,
) : BeanPostProcessor,
    Ordered {
    private val decoratorFactory = MetricDecoratorFactory(metrics)

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        val meteredBean = bean.meteredBean(beanName)
        if (meteredBean !== bean) {
            log.info {
                "Metered bean [$beanName] [${bean.javaClass.name}] -> [${meteredBean.javaClass.name}]"
            }
        }
        return meteredBean
    }

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE

    private fun Any.meteredBean(beanName: String): Any = when (this) {
        is EventStoreBinding -> {
            val meteredEventStore = decoratorFactory.decorate(eventStore, name) as EventStore
            if (meteredEventStore === eventStore) this else copy(eventStore = meteredEventStore)
        }

        is SnapshotStoreBinding -> {
            val meteredSnapshotStore = decoratorFactory.decorate(snapshotStore, name) as SnapshotStore
            if (meteredSnapshotStore === snapshotStore) this else copy(snapshotStore = meteredSnapshotStore)
        }

        else -> decoratorFactory.decorate(this, beanName)
    }

    private companion object {
        val log = KotlinLogging.logger {}
    }
}
