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
import reactor.core.publisher.Sinks

class BatchEnqueueErrorTest {
    @Test
    fun `terminal failure should take precedence over the sink result`() {
        val failure = IllegalStateException("terminal")

        Sinks.EmitResult.FAIL_TERMINATED
            .toBatchEnqueueError("test", 8, failure)
            .assert()
            .isSameAs(failure)
    }

    @Test
    fun `overflow should preserve coordinator capacity context`() {
        val error = Sinks.EmitResult.FAIL_OVERFLOW
            .toBatchEnqueueError("test", 8, null)

        error.assert().isInstanceOf(BatchOverflowException::class.java)
        (error as BatchOverflowException).coordinatorName.assert().isEqualTo("test")
        error.maxPendingItems.assert().isEqualTo(8)
    }

    @Test
    fun `cancelled and terminated sinks should report a closed coordinator`() {
        listOf(
            Sinks.EmitResult.FAIL_CANCELLED,
            Sinks.EmitResult.FAIL_TERMINATED,
        ).forEach { emitResult ->
            val error = emitResult.toBatchEnqueueError("test", 8, null)

            error.assert().isInstanceOf(BatchClosedException::class.java)
            (error as BatchClosedException).coordinatorName.assert().isEqualTo("test")
        }
    }

    @Test
    fun `unexpected sink failures should retain the emit result`() {
        val error = Sinks.EmitResult.FAIL_NON_SERIALIZED
            .toBatchEnqueueError("test", 8, null)

        error.assert().isInstanceOf(IllegalStateException::class.java)
        error.message.assert()
            .isEqualTo("Failed to enqueue batch item[test]: FAIL_NON_SERIALIZED")
    }
}
