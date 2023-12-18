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

package me.ahoo.wow.compensation.server

import me.ahoo.wow.compensation.domain.CompensationSpec
import me.ahoo.wow.compensation.domain.DefaultCompensationSpec
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = CompensationProperties.PREFIX)
data class CompensationProperties(
    override val maxRetries: Int = DefaultCompensationSpec.maxRetries,
    override val minBackoff: Duration = DefaultCompensationSpec.minBackoff,
    override val executionTimeout: Duration = DefaultCompensationSpec.executionTimeout,
    val mutex: String = "compensation_mutex",
    val batchSize: Int = 100,
    val schedule: ScheduleProperties = ScheduleProperties()
) : CompensationSpec {
    companion object {
        const val PREFIX = me.ahoo.wow.spring.boot.starter.compensation.CompensationProperties.PREFIX
    }

    data class ScheduleProperties(
        val initialDelay: Duration = Duration.ofSeconds(60),
        val period: Duration = Duration.ofSeconds(60),
    )
}
