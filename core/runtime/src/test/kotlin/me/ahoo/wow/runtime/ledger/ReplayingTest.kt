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

package me.ahoo.wow.runtime.ledger

import kotlinx.coroutines.CancellationException
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import org.junit.jupiter.api.Test

/** A replay's failure ([replaying]): the aggregate's data, an `Error` of the application's code too, but the JVM's. */
class ReplayingTest {
    private val ref = AggregateType("shop", "cart").ref("t", "a")

    private fun failureOf(thrown: Throwable): Throwable? =
        runCatching { replaying(ref) { throw thrown } }.exceptionOrNull()

    @Test
    fun `an exception or an error of the application's code should be the history's that does not replay`() {
        listOf(IllegalStateException("bad"), NotImplementedError("not yet"), StackOverflowError()).forEach { thrown ->
            val failure = failureOf(thrown)
            failure.assert().isInstanceOf(UnreplayableHistoryException::class.java)
            failure!!.cause.assert().isSameAs(thrown)
            (failure as UnreplayableHistoryException).ref.assert().isEqualTo(ref)
        }
    }

    @Test
    fun `a fatal error and a cancellation should be thrown as they are`() {
        val fatal = OutOfMemoryError("heap")
        failureOf(fatal).assert().isSameAs(fatal)
        val cancelled = CancellationException("stopping")
        failureOf(cancelled).assert().isSameAs(cancelled)
    }
}
