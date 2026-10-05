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

package me.ahoo.wow.spring.boot.starter.event

import me.ahoo.wow.api.Wow
import me.ahoo.wow.processing.failure.FailureRecorder
import me.ahoo.wow.spring.boot.starter.BusProperties
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = EventProperties.PREFIX)
class EventProperties(
    @NestedConfigurationProperty var bus: BusProperties = BusProperties(),
    /**
     * Whether an event-processing failure that no failure recorder recorded (no compensation module) is acknowledged.
     * `true` (the default, the behaviour before 9.3.0) logs and acknowledges it; `false` leaves it unacknowledged so
     * the bus delivers it again.
     */
    @DefaultValue("true") var ackOnUnrecordedFailure: Boolean = true,
) {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}event"
        const val BUS_TYPE = "${PREFIX}${BusProperties.TYPE_SUFFIX_KEY}"
        const val BUS_LOCAL_FIRST_ENABLED = "${PREFIX}${BusProperties.LOCAL_FIRST_ENABLED_SUFFIX_KEY}"
    }
}

/** The application's [FailureRecorder] (the compensation module's), or [FailureRecorder.NONE]. */
fun ObjectProvider<FailureRecorder>.orNone(): FailureRecorder = getIfAvailable { FailureRecorder.NONE }

/** [EventProperties.ackOnUnrecordedFailure], `true` without the event properties. */
fun ObjectProvider<EventProperties>.ackOnUnrecordedFailure(): Boolean =
    getIfAvailable()?.ackOnUnrecordedFailure ?: true
