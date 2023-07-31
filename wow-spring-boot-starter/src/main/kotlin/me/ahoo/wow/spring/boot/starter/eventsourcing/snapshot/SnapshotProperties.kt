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

import me.ahoo.wow.spring.boot.starter.EnabledCapable
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingProperties
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = SnapshotProperties.PREFIX)
data class SnapshotProperties(
    @DefaultValue("true")
    override var enabled: Boolean = true,
    var storage: SnapshotStorage = SnapshotStorage.MONGO
) : EnabledCapable {
    companion object {
        const val PREFIX = "${EventSourcingProperties.PREFIX}.snapshot"
        const val STORAGE = "$PREFIX.storage"
    }
}

enum class SnapshotStorage {
    MONGO,
    REDIS,
    R2DBC,
    ELASTICSEARCH,
    IN_MEMORY,
    DELAY
    ;

    companion object {
        const val MONGO_NAME = "mongo"
        const val REDIS_NAME = "redis"
        const val R2DBC_NAME = "r2dbc"
        const val ELASTICSEARCH_NAME = "elasticsearch"
        const val IN_MEMORY_NAME = "in_memory"
        const val DELAY_NAME = "delay"
    }
}
