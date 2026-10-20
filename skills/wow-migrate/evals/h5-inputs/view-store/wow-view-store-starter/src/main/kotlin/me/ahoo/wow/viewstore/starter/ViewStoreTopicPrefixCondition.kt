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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.Wow
import me.ahoo.wow.spring.boot.starter.BusType
import me.ahoo.wow.spring.boot.starter.command.CommandProperties
import me.ahoo.wow.spring.boot.starter.event.EventProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.state.StateProperties
import me.ahoo.wow.spring.boot.starter.kafka.ConditionalOnKafkaEnabled
import me.ahoo.wow.spring.boot.starter.kafka.KafkaProperties
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata

/** [ViewStoreKafkaProperties.TOPIC_PREFIX], or null when it is unset or blank: a blank prefix is no prefix. */
internal fun Environment.viewStoreTopicPrefix(): String? = Binder.get(this)
    .bind(ViewStoreKafkaProperties.TOPIC_PREFIX, String::class.java)
    .orElse(null)
    ?.takeIf { it.isNotBlank() }

/** Matches when the view store has a Kafka topic prefix of its own ([viewStoreTopicPrefix]). */
internal class OnViewStoreTopicPrefixCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        context.environment.viewStoreTopicPrefix() != null
}

/**
 * The startup warning of a host whose view store topics are Wow's default ones (`wow.view-store.view.command`, …):
 * the view store has no prefix of its own, the host keeps Wow's default `wow.kafka.topic-prefix`, and Kafka carries at
 * least one bus. Any other deployment of the view store on that Kafka cluster in the same state (another host, or a
 * standalone server configured so) shares these topics, and each consumes the other's commands and events. Null when
 * the topics are the deployment's own, or no bus is on Kafka.
 */
internal fun Environment.viewStoreSharedTopicsWarning(): String? {
    if (viewStoreTopicPrefix() != null) return null
    if (!getProperty(ConditionalOnKafkaEnabled.ENABLED_KEY, "true").equals("true", ignoreCase = true)) return null
    val hostPrefix = Binder.get(this).bind("${KafkaProperties.PREFIX}.topic-prefix", String::class.java)
        .orElse(Wow.WOW_PREFIX)
    if (hostPrefix != Wow.WOW_PREFIX) return null
    val onKafka = listOf(CommandProperties.BUS_TYPE, EventProperties.BUS_TYPE, StateProperties.BUS_TYPE).any {
        getProperty(it, BusType.KAFKA_NAME).equals(BusType.KAFKA_NAME, ignoreCase = true)
    }
    if (!onKafka) return null
    return "The view store's Kafka topics are Wow's default ones (${Wow.WOW_PREFIX}view-store.view.command, …): " +
        "another deployment of the view store on the same Kafka cluster under the same names would consume this " +
        "one's commands and events. Set ${ViewStoreKafkaProperties.TOPIC_PREFIX} to a prefix of this deployment's " +
        "own (the compensation service uses wow.compensation-service.); changing it moves the view store to new, " +
        "empty topics, so drain the old ones first."
}

/** Logs [viewStoreSharedTopicsWarning] once, after the singletons are created. */
internal class ViewStoreSharedTopicsWarning(private val environment: Environment) : SmartInitializingSingleton {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    override fun afterSingletonsInstantiated() {
        environment.viewStoreSharedTopicsWarning()?.let { warning -> log.warn { warning } }
    }
}
