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

package me.ahoo.wow.spring.boot.starter.mongo

import me.ahoo.wow.infra.batch.BatchOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = MongoSnapshotStoreBatchProperties.PREFIX)
class MongoSnapshotStoreBatchProperties(
    var enabled: Boolean = false,
    var maxSize: Int = BatchOptions.DEFAULT_MAX_SIZE,
    var maxDelay: Duration = BatchOptions.DEFAULT_MAX_DELAY,
    var maxPendingItems: Int = BatchOptions.DEFAULT_MAX_PENDING_ITEMS,
    var laneCount: Int = BatchOptions.DEFAULT_LANE_COUNT,
) {
    fun toOptions(): BatchOptions? {
        if (!enabled) {
            return null
        }
        return BatchOptions(
            maxSize = maxSize,
            maxDelay = maxDelay,
            maxPendingItems = maxPendingItems,
            laneCount = laneCount,
        )
    }

    companion object {
        const val PREFIX = "${MongoProperties.PREFIX}.snapshot-store-batch"
    }
}
