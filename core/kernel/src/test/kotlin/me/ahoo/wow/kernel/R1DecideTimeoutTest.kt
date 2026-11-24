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

package me.ahoo.wow.kernel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

/**
 * Deep review r1 (kernel): a decide function that bounds a remote call with `withTimeout`, the coroutine way to bound
 * any call, lets a [TimeoutCancellationException] out when the call is late. [Decisions] rethrows every
 * [CancellationException] as it is, and so does [Kernel.execute], so the kernel reports the call's timeout as its
 * caller's cancellation, although the caller is still waiting: [ThrownRejections]' KDoc and the rejections guide say a
 * remote call's timeout is a failure of the command's handling. The runtime's `LocalExecution` rethrows a
 * cancellation too, so the command ends with neither a FAILED result nor its error logged.
 */
class R1DecideTimeoutTest {
    class Quote
    data class Quoted(val price: Int)

    class Product(val id: String) {
        var price: Int = 0
    }

    private val trail = CopyOnWriteArrayList<String>()

    private val model = aggregate<Product>("test", "product") {
        initial { Product(it.id) }
        decideSuspending<Quote>(Creation.ALLOWED) { _, _ ->
            onAppendFailure { trail += "undone" }
            // A remote call that does not answer in time.
            val price = withTimeout(2.seconds) { awaitCancellation() }
            Decision.events(Quoted(price))
        }
        evolve<Quoted> { price = it.price }
    }
    private val ref = model.type.ref("t", "p-1")

    @Test
    fun `a call in a decide function that times out is a failure of the command, not its caller's cancellation`() =
        runTest {
            val kernel = Kernel(InMemoryEventLog())
            val failure = runCatching { kernel.execute(model, Command("c-1", "r-1", ref, Quote())) }.exceptionOrNull()
            trail.assert().containsExactly("undone")
            failure.assert().isNotNull()
            // The caller was not cancelled: what it is told must not read as its own cancellation.
            failure.assert().isNotInstanceOf(CancellationException::class.java)
        }
}
