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

package me.ahoo.wow.viewstore.starter

import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.kafka.AggregateTopicConverter
import me.ahoo.wow.kafka.CommandTopicConverter
import me.ahoo.wow.kafka.DefaultCommandTopicConverter
import me.ahoo.wow.kafka.DefaultEventStreamTopicConverter
import me.ahoo.wow.kafka.DefaultStateEventTopicConverter
import me.ahoo.wow.kafka.EventStreamTopicConverter
import me.ahoo.wow.kafka.StateEventTopicConverter
import me.ahoo.wow.viewstore.ViewStoreService
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * Gives the view store's aggregates Kafka topics under a prefix of their own ([ViewStoreKafkaProperties.topicPrefix]),
 * so that two deployments of the view store on one Kafka cluster (a host embedding the starter beside the standalone
 * server, or two hosts) do not consume each other's commands and events, while the host's own aggregates keep
 * exactly the topics the host's converters give them.
 *
 * Every Kafka bus takes its topics from the host's [CommandTopicConverter], [EventStreamTopicConverter] and
 * [StateEventTopicConverter] beans; this wraps each so that it answers an aggregate of the view store's context with
 * Wow's default naming under the view store's prefix, and hands every other aggregate to the host's converter.
 *
 * A host bean that is several of these converters at once is refused at startup: its one `convert` does not say which
 * kind is asked, so the view store could not tell a command topic from an event one and would put its aggregates on
 * the wrong topics. Wow's own converter beans are one kind each; a host with such a bean splits it into one bean per
 * kind, or leaves `wow.view-store.kafka.topic-prefix` unset.
 *
 * A blank prefix is no prefix: the starter registers this only for a prefix with text in it.
 */
class ViewStoreTopicConverterPostProcessor(private val topicPrefix: String) : BeanPostProcessor {
    init {
        require(topicPrefix.isNotBlank()) { "The view store's topic prefix must not be blank." }
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean !is AggregateTopicConverter || bean.isViewStoreTopicConverter()) {
            return bean
        }
        val kinds = listOf(
            CommandTopicConverter::class.java,
            EventStreamTopicConverter::class.java,
            StateEventTopicConverter::class.java,
        ).filter { it.isInstance(bean) }
        check(kinds.size <= 1) {
            "Bean '$beanName' (${bean.javaClass.name}) is several Kafka topic converters at once " +
                "(${kinds.joinToString { it.simpleName }}), so wow.view-store.kafka.topic-prefix cannot tell which " +
                "kind of topic it is asked for. Declare one converter bean per kind, or leave " +
                "wow.view-store.kafka.topic-prefix unset."
        }
        return when (bean) {
            is CommandTopicConverter ->
                ViewStoreCommandTopicConverter(bean, DefaultCommandTopicConverter(topicPrefix))
            is EventStreamTopicConverter ->
                ViewStoreEventStreamTopicConverter(bean, DefaultEventStreamTopicConverter(topicPrefix))
            is StateEventTopicConverter ->
                ViewStoreStateEventTopicConverter(bean, DefaultStateEventTopicConverter(topicPrefix))
            else -> bean
        }
    }
}

private fun Any.isViewStoreTopicConverter(): Boolean =
    this is ViewStoreCommandTopicConverter ||
        this is ViewStoreEventStreamTopicConverter ||
        this is ViewStoreStateEventTopicConverter

/** Whether this is one of the view store's aggregates (`view`, `view_preferences`). */
fun NamedAggregate.isViewStoreAggregate(): Boolean = contextName == ViewStoreService.SERVICE_NAME

class ViewStoreCommandTopicConverter(
    private val host: CommandTopicConverter,
    private val viewStore: AggregateTopicConverter,
) : CommandTopicConverter {
    override fun convert(namedAggregate: NamedAggregate): String =
        if (namedAggregate.isViewStoreAggregate()) viewStore.convert(namedAggregate) else host.convert(namedAggregate)
}

class ViewStoreEventStreamTopicConverter(
    private val host: EventStreamTopicConverter,
    private val viewStore: AggregateTopicConverter,
) : EventStreamTopicConverter {
    override fun convert(namedAggregate: NamedAggregate): String =
        if (namedAggregate.isViewStoreAggregate()) viewStore.convert(namedAggregate) else host.convert(namedAggregate)
}

class ViewStoreStateEventTopicConverter(
    private val host: StateEventTopicConverter,
    private val viewStore: AggregateTopicConverter,
) : StateEventTopicConverter {
    override fun convert(namedAggregate: NamedAggregate): String =
        if (namedAggregate.isViewStoreAggregate()) viewStore.convert(namedAggregate) else host.convert(namedAggregate)
}
