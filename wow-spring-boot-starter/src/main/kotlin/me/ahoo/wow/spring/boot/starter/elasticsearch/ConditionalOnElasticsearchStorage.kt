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

import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnEventStoreStorage
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ConditionalOnSnapshotStoreStorage
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConfigurationCondition.ConfigurationPhase

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(OnElasticsearchStorageCondition::class)
annotation class ConditionalOnElasticsearchStorage

class OnElasticsearchStorageCondition : AnyNestedCondition(ConfigurationPhase.REGISTER_BEAN) {
    @ConditionalOnEventStoreStorage(StorageType.ELASTICSEARCH)
    interface EventStoreUsesElasticsearch

    @ConditionalOnSnapshotStoreStorage(StorageType.ELASTICSEARCH)
    interface SnapshotStoreUsesElasticsearch
}
