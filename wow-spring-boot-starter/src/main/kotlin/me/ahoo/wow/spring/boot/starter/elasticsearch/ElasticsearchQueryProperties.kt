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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = ElasticsearchQueryProperties.PREFIX)
class ElasticsearchQueryProperties(
    @DefaultValue("10000") val batchSize: Int = 10_000,
    @DefaultValue("1m") val keepAlive: Duration = Duration.ofMinutes(1),
) {
    init {
        require(batchSize in 1..10_000) { "batchSize must be between 1 and 10000." }
        require(keepAlive.toMillis() > 0) { "keepAlive must be greater than or equal to 1ms." }
    }

    companion object {
        const val PREFIX = "${ElasticsearchProperties.PREFIX}.query"
    }
}
