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

package me.ahoo.wow.spring.query

import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory
import org.springframework.beans.factory.ObjectProvider
import kotlin.reflect.KClass

class LegacySpringQueryApiSourceCompatibilityTest {
    @Suppress("Unused")
    private fun compileOnly(
        snapshotFactoryProvider: ObjectProvider<SnapshotQueryServiceFactory>,
        eventStreamFactoryProvider: ObjectProvider<EventStreamQueryServiceFactory>,
    ): Set<KClass<out QueryServiceRegistrar>> {
        SnapshotQueryServiceRegistrar()
        EventStreamQueryServiceRegistrar()
        snapshotFactoryProvider.getOrNoOp()
        eventStreamFactoryProvider.getOrNoOp()
        return setOf(
            QueryServiceRegistrar::class,
            SnapshotQueryServiceRegistrar::class,
            EventStreamQueryServiceRegistrar::class,
        )
    }
}
