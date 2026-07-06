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

package me.ahoo.wow.spring.boot.starter.mock

import me.ahoo.wow.eventsourcing.mock.DelaySnapshotStore
import me.ahoo.wow.spring.boot.starter.ConditionalOnWowEnabled
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.SnapshotStoreBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.snapshot.ConditionalOnSnapshotEnabled
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnWowEnabled
@ConditionalOnSnapshotEnabled
@ConditionalOnClass(DelaySnapshotStore::class)
class MockSnapshotAutoConfiguration {

    @Bean(name = ["delaySnapshotStore", "delaySnapshotRepository"])
    @ConditionalOnSnapshotStoreStorage(StorageType.DELAY)
    fun delaySnapshotStore(): DelaySnapshotStore {
        return DelaySnapshotStore()
    }

    @Bean
    @ConditionalOnSnapshotStoreStorage(StorageType.DELAY)
    fun delaySnapshotStoreBinding(delaySnapshotStore: DelaySnapshotStore): SnapshotStoreBinding {
        return SnapshotStoreBinding.storage(StorageType.DELAY, delaySnapshotStore)
    }
}
