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
import java.time.Duration

/**
 * Observability's configuration, `wow.opentelemetry.*`, read when wow-opentelemetry is on the classpath
 * ([ObservabilityAutoConfiguration]).
 */
@InternalWowApi
@ConfigurationProperties(prefix = ObservabilityProperties.PREFIX)
class ObservabilityProperties {
    /** Whether the node is traced and measured. */
    var enabled: Boolean = true

    /**
     * How often the gauges that are read, not heard, are sampled: the parked entries, the subscriptions' positions and
     * a rebuild's progress.
     */
    var sampleInterval: Duration = DEFAULT_SAMPLE_INTERVAL

    @InternalWowApi
    companion object {
        const val PREFIX: String = "wow.opentelemetry"
        private val DEFAULT_SAMPLE_INTERVAL: Duration = Duration.ofSeconds(15)
    }
}
