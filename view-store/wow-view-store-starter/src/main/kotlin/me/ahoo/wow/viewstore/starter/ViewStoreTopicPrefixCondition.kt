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
