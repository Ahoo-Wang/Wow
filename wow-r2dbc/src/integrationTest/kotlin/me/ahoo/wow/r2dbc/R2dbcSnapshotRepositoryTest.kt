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
package me.ahoo.wow.r2dbc

import me.ahoo.wow.eventsourcing.snapshot.SnapshotRepository
import me.ahoo.wow.tck.container.MariaDbTestFixture
import me.ahoo.wow.tck.eventsourcing.snapshot.SnapshotRepositorySpec
import org.junit.jupiter.api.extension.RegisterExtension

internal class R2dbcSnapshotRepositoryTest : SnapshotRepositorySpec() {
    @JvmField
    @RegisterExtension
    val mariaDb = MariaDbTestFixture()

    override fun createSnapshotRepository(): SnapshotRepository {
        val simpleSnapshotSchema = SimpleSnapshotSchema()
        return R2dbcSnapshotRepository(
            SimpleDatabase(ConnectionFactoryProviders.create(mariaDb.r2dbcUrl(poolSize = 2))),
            simpleSnapshotSchema,
        )
    }
}
