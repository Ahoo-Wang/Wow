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

package me.ahoo.wow.mongo.subscription

import kotlinx.coroutines.runBlocking
import me.ahoo.test.asserts.assert
import me.ahoo.wow.mongo.log.MongoLogFixture
import me.ahoo.wow.spi.subscription.LeaseStore
import me.ahoo.wow.tck.MutableClock
import me.ahoo.wow.tck.subscription.LeaseStoreSpec
import org.junit.jupiter.api.Test
import java.time.Clock
import kotlin.time.Duration.Companion.seconds

class MongoLeaseStoreTest : LeaseStoreSpec() {
    override fun createStore(clock: Clock): LeaseStore =
        MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE, clock)

    @Test
    fun `an owner name that looks like a field path is stored as is`(): Unit = runBlocking {
        val store = createStore(MutableClock())
        val group = "field-path-" + System.nanoTime()
        store.acquire(group, 0, "\$owner", 10.seconds)?.owner.assert().isEqualTo("\$owner")
        store.acquire(group, 0, "\$owner", 10.seconds)?.epoch.assert().isEqualTo(1L)
    }

    private companion object {
        init {
            MongoLeaseStore(MongoLogFixture.client, MongoLogFixture.DATABASE).createSchema()
        }
    }
}
