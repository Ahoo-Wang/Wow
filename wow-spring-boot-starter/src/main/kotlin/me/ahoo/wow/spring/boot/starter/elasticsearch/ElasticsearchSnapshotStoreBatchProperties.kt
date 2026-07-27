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

import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchSnapshotStoreBatchOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = ElasticsearchSnapshotStoreBatchProperties.PREFIX)
class ElasticsearchSnapshotStoreBatchProperties(
    @DefaultValue("false") val enabled: Boolean = false,
    @DefaultValue("128") val maxSize: Int = ElasticsearchSnapshotStoreBatchOptions.DEFAULT_MAX_SIZE,
    @DefaultValue("1ms") val maxDelay: Duration = ElasticsearchSnapshotStoreBatchOptions.DEFAULT_MAX_DELAY,
    @DefaultValue("4096")
    val maxPendingSaves: Int = ElasticsearchSnapshotStoreBatchOptions.DEFAULT_MAX_PENDING_SAVES,
    @DefaultValue("1") val laneCount: Int = ElasticsearchSnapshotStoreBatchOptions.DEFAULT_LANE_COUNT,
) {
    fun toOptions(): ElasticsearchSnapshotStoreBatchOptions {
        return ElasticsearchSnapshotStoreBatchOptions(
            enabled = enabled,
            maxSize = maxSize,
            maxDelay = maxDelay,
            maxPendingSaves = maxPendingSaves,
            laneCount = laneCount,
        )
    }

    companion object {
        const val PREFIX = "${ElasticsearchProperties.PREFIX}.snapshot-store-batch"
    }
}
