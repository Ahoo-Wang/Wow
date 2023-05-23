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

package me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot

import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.ConstructorBinding
import java.time.Duration

const val DEFAULT_VERSION_OFFSET = 5
val DEFAULT_TIME_OFFSET: Duration = Duration.ofDays(1)

@ConstructorBinding
@ConfigurationProperties(prefix = SnapshotProperties.PREFIX)
data class SnapshotProperties(
    val enabled: Boolean = true,
    val strategy: Strategy = Strategy.ALL,
    val storage: SnapshotStorage = SnapshotStorage.MONGO,
    val versionOffset: Int = DEFAULT_VERSION_OFFSET,
    val timeOffset: Duration = DEFAULT_TIME_OFFSET,
) {
    companion object {
        const val PREFIX = "${EventSourcingProperties.PREFIX}.snapshot"
        const val STRATEGY = "$PREFIX.strategy"
        const val STORAGE = "$PREFIX.storage"
    }
}

enum class Strategy {
    ALL,
    VERSION,
    TIME,
    ;

    companion object {
        const val ALL_NAME = "all"
        const val VERSION_NAME = "version"
        const val TIME_NAME = "time"
    }
}

enum class SnapshotStorage {
    MONGO,
    R2DBC,
    ELASTICSEARCH,
    IN_MEMORY,
    DELAY,
    ;

    companion object {
        const val MONGO_NAME = "mongo"
        const val R2DBC_NAME = "r2dbc"
        const val ELASTICSEARCH_NAME = "elasticsearch"
        const val IN_MEMORY_NAME = "in_memory"
        const val DELAY_NAME = "delay"
    }
}
