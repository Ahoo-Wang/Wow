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

package me.ahoo.wow.mongo

import com.mongodb.reactivestreams.client.MongoDatabase
import io.mockk.mockk
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class MongoSnapshotRepositoryCompatibilityTest {

    @Test
    fun `should keep mongo snapshot repository as jvm visible type`() {
        MongoSnapshotRepository::class.java.name.assert()
            .isEqualTo("me.ahoo.wow.mongo.MongoSnapshotRepository")
        MongoSnapshotRepository.NAME.assert().isEqualTo(MongoSnapshotStore.NAME)
        MongoSnapshotRepository.DEFAULT_REPLACE_OPTIONS.assert()
            .isSameAs(MongoSnapshotStore.DEFAULT_REPLACE_OPTIONS)
        MongoSnapshotRepository(mockk<MongoDatabase>()).name.assert().isEqualTo(MongoSnapshotStore.NAME)
    }
}
