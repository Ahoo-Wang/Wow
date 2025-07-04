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

package me.ahoo.wow.compensation.server.configuration

import me.ahoo.wow.api.annotation.Retry
import me.ahoo.wow.compensation.api.IRetrySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = CompensationProperties.PREFIX)
data class CompensationProperties(
    val host: String = "",
    @DefaultValue("10")
    override val maxRetries: Int = Retry.DEFAULT_MAX_RETRIES,
    @DefaultValue("180")
    override val minBackoff: Int = Retry.DEFAULT_MIN_BACKOFF,
    @DefaultValue("120")
    override val executionTimeout: Int = Retry.DEFAULT_EXECUTION_TIMEOUT,
) : IRetrySpec {
    companion object {
        const val PREFIX = me.ahoo.wow.spring.boot.starter.compensation.CompensationProperties.PREFIX
    }
}
