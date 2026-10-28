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

import me.ahoo.wow.api.annotation.InternalWowApi
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Observability's configuration, `wow.observability.*`, read when wow-observability is on the classpath
 * ([ObservabilityAutoConfiguration]).
 */
@InternalWowApi
@ConfigurationProperties(prefix = ObservabilityProperties.PREFIX)
class ObservabilityProperties {
    /** Whether the node is traced and measured. */
    var enabled: Boolean = true

    @InternalWowApi
    companion object {
        const val PREFIX: String = "wow.observability"
    }
}
