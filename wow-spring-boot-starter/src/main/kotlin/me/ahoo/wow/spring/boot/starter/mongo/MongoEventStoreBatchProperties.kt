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

import me.ahoo.wow.mongo.MongoEventStoreBatchOptions
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.time.Duration

@ConfigurationProperties(prefix = MongoEventStoreBatchProperties.PREFIX)
class MongoEventStoreBatchProperties(
    @DefaultValue("false") val enabled: Boolean = false,
    @DefaultValue("128") val maxSize: Int = MongoEventStoreBatchOptions.DEFAULT_MAX_SIZE,
    @DefaultValue("1ms") val maxDelay: Duration = MongoEventStoreBatchOptions.DEFAULT_MAX_DELAY,
    @DefaultValue("4096")
    val maxPendingAppends: Int = MongoEventStoreBatchOptions.DEFAULT_MAX_PENDING_APPENDS,
) {
    fun toOptions(): MongoEventStoreBatchOptions {
        return MongoEventStoreBatchOptions(
            enabled = enabled,
            maxSize = maxSize,
            maxDelay = maxDelay,
            maxPendingAppends = maxPendingAppends,
        )
    }

    companion object {
        const val PREFIX = "${MongoProperties.PREFIX}.event-store-batch"
    }
}
