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

package me.ahoo.wow.infra.batch

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BatchResultDispatcherTest {
    @Test
    fun `nested dispatch after shutdown should preserve callback context`() {
        val nestedDispatched = CountDownLatch(1)
        val terminated = CountDownLatch(1)
        lateinit var dispatcher: BatchResultDispatcher
        dispatcher = BatchResultDispatcher(
            name = "test",
            maxPendingItems = 2,
            onTerminated = terminated::countDown,
        )

        dispatcher.isDispatchingResult.assert().isFalse()
        dispatcher.dispatch {
            dispatcher.isDispatchingResult.assert().isTrue()
            dispatcher.shutdown()
            dispatcher.dispatch {
                dispatcher.isDispatchingResult.assert().isTrue()
                nestedDispatched.countDown()
            }
        }

        nestedDispatched.await(1, TimeUnit.SECONDS).assert().isTrue()
        terminated.await(1, TimeUnit.SECONDS).assert().isTrue()
    }
}
