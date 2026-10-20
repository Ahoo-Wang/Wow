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

import me.ahoo.wow.api.naming.EnabledCapable
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The view store's settings.
 *
 * @property enabled whether the starter adds the view store to the host.
 * @property systemViews the read-only views the default [me.ahoo.wow.viewstore.starter.system.SystemViewProvider]
 * serves; a `SystemViewProvider` bean of the host's replaces it.
 * @property kafka the Kafka topics of the view store's aggregates.
 */
@ConfigurationProperties(prefix = ViewStoreProperties.PREFIX)
class ViewStoreProperties(
    override var enabled: Boolean = true,
    var systemViews: List<SystemViewProperties> = emptyList(),
    var kafka: ViewStoreKafkaProperties = ViewStoreKafkaProperties(),
) : EnabledCapable {
    companion object {
        const val PREFIX = "wow.view-store"
    }
}

/**
 * The Kafka topics of the view store's aggregates.
 *
 * @property topicPrefix the topic prefix of the view store's aggregates only, in place of `wow.kafka.topic-prefix`.
 * Unset or blank, they share the host's prefix. Set it when another deployment of the view store uses the same Kafka cluster
 * under the same prefix; the host's own aggregates keep their topics either way.
 */
class ViewStoreKafkaProperties(
    var topicPrefix: String? = null,
) {
    companion object {
        const val TOPIC_PREFIX = "${ViewStoreProperties.PREFIX}.kafka.topic-prefix"
    }
}

/**
 * One configured system view.
 *
 * @property tenantId the tenant it is for; blank is every tenant.
 * @property appId the application it is for; blank is every application.
 * @property id its id, which must not start with `system:`.
 * @property definitionId the definition it is a view of.
 * @property title its title.
 * @property config the engine's `ViewConfig` as JSON text.
 */
class SystemViewProperties(
    var tenantId: String = "",
    var appId: String = "",
    var id: String = "",
    var definitionId: String = "",
    var title: String = "",
    var config: String = "",
)
