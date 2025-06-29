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

package me.ahoo.wow.compensation.server.scheduler

import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.compensation.server.configuration.CompensationProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = SchedulerProperties.PREFIX)
data class SchedulerProperties(
    @DefaultValue("true")
    override var enabled: Boolean = true,
    @DefaultValue("compensation_mutex")
    val mutex: String = "compensation_mutex",
    @DefaultValue("100")
    val batchSize: Int = 100,
    @DefaultValue("PT60S")
    val initialDelay: Duration = Duration.ofSeconds(60),
    @DefaultValue("PT60S")
    val period: Duration = Duration.ofSeconds(60),
) : EnabledCapable {
    companion object {
        const val PREFIX = CompensationProperties.PREFIX + ".scheduler"
    }
}
