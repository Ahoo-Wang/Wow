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

import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.eventsourcing.snapshot.DEFAULT_VERSION_OFFSET
import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = SnapshotProperties.PREFIX)
data class SnapshotProperties(
    override var enabled: Boolean = true,
    var strategy: Strategy = Strategy.ALL,
    var versionOffset: Int = DEFAULT_VERSION_OFFSET,
    var storage: StorageType = StorageType.MONGO,
    /**
     * An application-registered `SnapshotStoreBinding` (and the `QueryBackendProvider` of the same name for snapshot
     * queries) used as the default instead of the built-in [storage]; the two exclude each other.
     */
    var binding: String? = null,
) : EnabledCapable {
    /** The built-in storage the default uses, or `null` when the default is a [binding]. */
    val defaultStorage: StorageType?
        get() = if (binding.isNullOrBlank()) storage else null

    companion object {
        const val PREFIX = "${EventSourcingProperties.PREFIX}.snapshot"
        const val STRATEGY = "$PREFIX.strategy"
        const val STORAGE = "$PREFIX.storage"
        const val BINDING = "$PREFIX.binding"
    }
}

enum class Strategy {
    ALL,
    VERSION_OFFSET,
    ;

    companion object {
        const val ALL_NAME = "all"
        const val VERSION_OFFSET_NAME = "version_offset"
    }
}
