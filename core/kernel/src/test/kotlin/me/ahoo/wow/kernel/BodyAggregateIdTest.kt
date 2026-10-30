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

import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.test.asserts.assertThrownBy
import me.ahoo.wow.api.aggregate.AggregateIdSource
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.decide.Misuse
import me.ahoo.wow.api.schedule.ScheduleRule
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/** A command whose body holds the id of its aggregate (`@AggregateId`), as a model reads it and the kernel checks it. */
class BodyAggregateIdTest {
    data class Stock(val productId: String?)
    data class Restock(val productId: String, val quantity: Int)
    data class Remind(val productId: String, val about: String)
    data class Note(val text: String)
    data class Stocked(val quantity: Int)

    class Product(val id: String)

    private val shipment = AggregateType("test", "shipment")
    private val model = aggregate<Product>("test", "product") {
        initial { Product(it.id) }
        decide<Stock>(Creation.REQUIRED) { _, _ -> Decision.events(Stocked(0)) }
        decide<Restock> { _, body -> Decision.events(Stocked(body.quantity)) }
        decide<Remind> { _, body ->
            // Restock's body names the product it restocks: here, the one named in the reminder.
            schedule("restock", ScheduleRule.after(5.minutes), Restock(body.about, 1))
            Decision.NoOp
        }
        decide<Note> { _, body ->
            schedule("ship", ScheduleRule.after(5.minutes), Note(body.text), shipment.ref("t", "s-1"))
            Decision.NoOp
        }
        evolve<Stocked> { }
        aggregateId<Stock> { it.productId }
        aggregateId<Restock> { it.productId }
    }

    @Test
    fun `a model reads the aggregate id of the commands whose body holds it, and of no other`() {
        model.idFromBody(Stock::class.java).assert().isTrue()
        model.idFromBody(Note::class.java).assert().isFalse()
        model.aggregateIdOf(Restock("mug", 1)).assert().isEqualTo("mug")
        // A creation may leave it out, and a command whose body holds none names none.
        model.aggregateIdOf(Stock(null)).assert().isNull()
        model.aggregateIdOf(Note("n")).assert().isNull()
    }

    @Test
    fun `a model reads it once per command it decides, and never for an aggregate whose id is its owner's`() {
        assertThrownBy<IllegalArgumentException> {
            aggregate<Product>("test", "product") {
                aggregateId<Restock> { it.productId }
                aggregateId<Restock> { it.productId }
            }
        }.hasMessageContaining("reads the aggregate id of ${Restock::class.java.name} twice")
        assertThrownBy<IllegalArgumentException> {
            aggregate<Product>("test", "product") {
                initial { Product(it.id) }
                decide<Note> { _, _ -> Decision.NoOp }
                aggregateId<Restock> { it.productId }
            }
        }.hasMessageContaining("reads the aggregate id of [${Restock::class.java.name}] from their bodies")
        assertThrownBy<IllegalArgumentException> {
            aggregate<Product>("test", "product") {
                initial { Product(it.id) }
                id(AggregateIdSource.OWNER)
                decide<Restock> { _, _ -> Decision.NoOp }
                aggregateId<Restock> { it.productId }
            }
        }.hasMessageContaining("id is its owner's: no command of it reads its aggregate id from its body")
    }

    @Test
    fun `a scheduled command whose body names another aggregate than its target is the decide function's misuse`() =
        runTest {
            val names = object : CommandTypes {
                override fun nameOf(target: AggregateType, command: Class<*>): String? =
                    "test.shipment.Note".takeIf { target == shipment }

                override fun aggregateIdOf(target: AggregateType, command: Any): String? = (command as Note).text
            }
            val kernel = Kernel(InMemoryEventLog(partitions = 1), clock = { NOW }, commandTypes = names)
            val ref = model.type.ref("t", "mug")
            kernel.execute(model, Command("c-1", "r-1", ref, Stock("mug")))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
            // A schedule of its own aggregate: Restock's body names it.
            kernel.execute(model, Command("c-2", "r-2", ref, Remind("mug", "mug")))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
            runCatching { kernel.execute(model, Command("c-3", "r-3", ref, Remind("mug", "cup"))) }.exceptionOrNull()
                .assert().isInstanceOf(Misuse.Argument::class.java)
                .hasMessageContaining("schedules ${Restock::class.java.name} for test.product/t/mug")
                .hasMessageContaining("its body names the aggregate cup")
            // Another aggregate type's, read as its contract describes the body.
            kernel.execute(model, Command("c-4", "r-4", ref, Note("s-1")))
                .assert().isInstanceOf(CommandOutcome.Committed::class.java)
            runCatching { kernel.execute(model, Command("c-5", "r-5", ref, Note("s-2"))) }.exceptionOrNull()
                .assert().isInstanceOf(Misuse.Argument::class.java)
                .hasMessageContaining("its body names the aggregate s-2")
        }

    private companion object {
        const val NOW = 1_000_000L
    }
}
