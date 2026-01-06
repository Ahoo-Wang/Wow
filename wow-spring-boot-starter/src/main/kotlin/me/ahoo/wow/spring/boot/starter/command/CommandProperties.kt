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

package me.ahoo.wow.spring.boot.starter.command

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.spring.boot.starter.BusProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = CommandProperties.PREFIX)
class CommandProperties(
    @NestedConfigurationProperty var bus: BusProperties = BusProperties(),
    @NestedConfigurationProperty var idempotency: IdempotencyProperties = IdempotencyProperties()
) {
    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}command"
        const val BUS_TYPE = "${PREFIX}${BusProperties.TYPE_SUFFIX_KEY}"
        const val BUS_LOCAL_FIRST_ENABLED = "${PREFIX}${BusProperties.LOCAL_FIRST_ENABLED_SUFFIX_KEY}"
    }
}

class IdempotencyProperties(
    @DefaultValue("true") override var enabled: Boolean = true,
    @NestedConfigurationProperty var bloomFilter: BloomFilter = BloomFilter()
) : EnabledCapable {
    companion object {
        const val PREFIX = "${CommandProperties.PREFIX}.idempotency"
    }

    data class BloomFilter(
        val ttl: Duration = Duration.ofMinutes(1),
        val expectedInsertions: Long = 1_000_000,
        val fpp: Double = 0.00001
    )
}
