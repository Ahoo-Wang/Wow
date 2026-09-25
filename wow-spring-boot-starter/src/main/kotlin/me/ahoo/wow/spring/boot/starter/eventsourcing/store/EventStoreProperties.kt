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

package me.ahoo.wow.spring.boot.starter.eventsourcing.store

import me.ahoo.wow.spring.boot.starter.eventsourcing.EventSourcingProperties
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The default event store: a built-in [storage], or a [binding] naming an application-registered
 * `EventStoreBinding` (and the `QueryBackendProvider` of the same name for event-stream queries). The two exclude
 * each other; when [binding] is set, [storage] is not used.
 */
@ConfigurationProperties(prefix = EventStoreProperties.PREFIX)
class EventStoreProperties(
    var storage: StorageType = StorageType.MONGO,
    var binding: String? = null,
) {
    /** The built-in storage the default uses, or `null` when the default is a [binding]. */
    val defaultStorage: StorageType?
        get() = if (binding.isNullOrBlank()) storage else null

    companion object {
        const val PREFIX = "${EventSourcingProperties.PREFIX}.store"
        const val STORAGE = "$PREFIX.storage"
        const val BINDING = "$PREFIX.binding"
    }
}
