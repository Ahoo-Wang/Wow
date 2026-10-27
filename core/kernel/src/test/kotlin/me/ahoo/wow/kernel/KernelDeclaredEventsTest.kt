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

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.lifecycle.AggregateDeleted
import me.ahoo.wow.kernel.memory.InMemoryEventLog
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The kernel holds a decide function and an after hook to the events they declare (their return type's, or their
 * `produces`): another is a failure of the model, never committed; a function whose events are unknown is not checked.
 */
class KernelDeclaredEventsTest {
    data class Open(val by: String)
    data class Close(val reason: String)
    data class Note(val text: String)
    data class Opened(val by: String)
    data class Closed(val reason: String)
    data class Noted(val text: String)
    data object Audited

    class Ticket

    private fun model(noteProduces: Set<Class<*>>?, auditProduces: Set<Class<*>>? = setOf(Audited::class.java)) =
        aggregate<Ticket>("test", "ticket") {
            initial { Ticket() }
            decide<Open>(Creation.REQUIRED, produces = setOf(Opened::class.java)) { _, body ->
                Decision.events(Opened(body.by))
            }
            // A composed lifecycle event is allowed when it is declared.
            decideSuspending<Close>(produces = setOf(Closed::class.java, AggregateDeleted::class.java)) { _, body ->
                Decision.events(Closed(body.reason), AggregateDeleted)
            }
            decideBlocking<Note>(produces = noteProduces) { _, body -> Decision.events(Noted(body.text), Audited) }
            after(include = setOf(Open::class.java), produces = auditProduces) { _, _ -> listOf(Audited) }
            evolve<Opened> { }
            evolve<Closed> { }
            evolve<Noted> { }
            evolve<Audited> { }
        }

    private val log = InMemoryEventLog()
    private val kernel = Kernel(log)
    private val ref = AggregateType("test", "ticket").ref("t", "ticket-1")

    private suspend fun send(model: AggregateModel<Ticket>, id: String, body: Any) =
        kernel.execute(model, Command(id, id, ref, body))

    @Test
    fun `the events a function declares pass, a composed lifecycle event among them`(): Unit = runTest {
        val model = model(noteProduces = setOf(Noted::class.java, Audited::class.java))
        (send(model, "c-1", Open("ahoo")) as CommandOutcome.Committed).stream.events.size.assert().isEqualTo(2)
        (send(model, "c-2", Note("n")) as CommandOutcome.Committed).stream.events.size.assert().isEqualTo(2)
        (send(model, "c-3", Close("done")) as CommandOutcome.Committed).deleted.assert().isTrue()
    }

    @Test
    fun `an event a decide function does not declare fails the command, naming the model, the command and the event`():
        Unit = runTest {
            val model = model(noteProduces = setOf(Noted::class.java))
            send(model, "c-1", Open("ahoo"))
            val undeclared = assertThrows<UndeclaredEventException> { send(model, "c-2", Note("n")) }
            undeclared.event.assert().isEqualTo(Audited::class.java)
            undeclared.command.assert().isEqualTo(Note::class.java)
            undeclared.message.assert()
                .isEqualTo("test.ticket's decide function of Note produced Audited, which it does not declare: neither its return type nor its produces lists it.")
            log.read(ref).toList().size.assert().isEqualTo(1)
        }

    @Test
    fun `an event an after hook does not declare fails the command`(): Unit = runTest {
        val model = model(noteProduces = null, auditProduces = emptySet())
        assertThrows<UndeclaredEventException> { send(model, "c-1", Open("ahoo")) }
            .message.assert().contains("after hook of Open produced Audited")
    }

    @Test
    fun `a function whose events are unknown is not checked`(): Unit = runTest {
        val model = model(noteProduces = null, auditProduces = null)
        send(model, "c-1", Open("ahoo"))
        (send(model, "c-2", Note("n")) as CommandOutcome.Committed).stream.events.size.assert().isEqualTo(2)
    }
}
