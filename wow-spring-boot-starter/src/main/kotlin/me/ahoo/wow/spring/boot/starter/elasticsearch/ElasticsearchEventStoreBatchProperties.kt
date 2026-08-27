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

import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStoreBatchOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = ElasticsearchEventStoreBatchProperties.PREFIX)
class ElasticsearchEventStoreBatchProperties(
    @DefaultValue("false") val enabled: Boolean = false,
    @DefaultValue("128") val maxSize: Int = ElasticsearchEventStoreBatchOptions.DEFAULT_MAX_SIZE,
    @DefaultValue("1ms") val maxDelay: Duration = ElasticsearchEventStoreBatchOptions.DEFAULT_MAX_DELAY,
    @DefaultValue("4096")
    val maxPendingAppends: Int = ElasticsearchEventStoreBatchOptions.DEFAULT_MAX_PENDING_APPENDS,
    @DefaultValue("1") val laneCount: Int = ElasticsearchEventStoreBatchOptions.DEFAULT_LANE_COUNT,
) {
    fun toOptions(): ElasticsearchEventStoreBatchOptions {
        return ElasticsearchEventStoreBatchOptions(
            enabled = enabled,
            maxSize = maxSize,
            maxDelay = maxDelay,
            maxPendingAppends = maxPendingAppends,
            laneCount = laneCount,
        )
    }

    companion object {
        const val PREFIX = "${ElasticsearchProperties.PREFIX}.event-store-batch"
    }
}
