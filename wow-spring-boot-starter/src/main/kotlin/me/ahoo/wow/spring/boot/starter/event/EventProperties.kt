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
import me.ahoo.wow.spring.boot.starter.BusProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import org.springframework.boot.context.properties.NestedConfigurationProperty

@ConstructorBinding
@ConfigurationProperties(prefix = EventProperties.PREFIX)
data class EventProperties(
    @NestedConfigurationProperty
    val bus: BusProperties = BusProperties()
) {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}event"
        const val BUS_TYPE = "${PREFIX}${BusProperties.TYPE_SUFFIX_KEY}"
        const val BUS_LOCAL_FIRST_ENABLED = "${PREFIX}${BusProperties.LOCAL_FIRST_ENABLED_SUFFIX_KEY}"
    }
}
