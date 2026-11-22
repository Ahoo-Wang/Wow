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

package me.ahoo.wow.test

import me.ahoo.test.asserts.assert
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.aggregate.Creation
import me.ahoo.wow.api.aggregate.Ownership
import me.ahoo.wow.api.annotation.AggregateId
import me.ahoo.wow.api.annotation.AggregateRoot
import me.ahoo.wow.api.annotation.AllowCreate
import me.ahoo.wow.api.annotation.OnEvent
import me.ahoo.wow.api.annotation.Saga
import me.ahoo.wow.api.client.CommandRequest
import me.ahoo.wow.api.client.toCommandRequest
import me.ahoo.wow.api.decide.Decision
import me.ahoo.wow.api.subscribe.StateTypedEvent
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.kernel.aggregate
import me.ahoo.wow.kernel.eachEvent
import org.junit.jupiter.api.Test

/**
 * A key held as an aggregate of its own, the slot: "at most one default template per tenant and kind" changes its
 * holder in one commit of the slot, and a saga lets the templates follow. `documentation/docs/{en,zh}/guide/
 * unique-keys.md` shows the slot, the saga and the specification.
 */
class DefaultTemplateSlotSpecTest {
    // The annotations below are inert here: the DSL reads none of them. They are the slot and the saga as the guide
    // shows them, which the compiler reads in a service's domain; this file's model and binding are built by hand
    // below, calling the classes' own functions, as the compiler generates them.
    // region slot
    /** Makes [templateId] the default template of its [kind]: the command goes to the slot whose id is the kind. */
    @AllowCreate
    data class ChooseDefaultTemplate(@AggregateId val kind: String, val templateId: String)

    /** [templateId] is the default template of the slot's kind, in place of [previous] (null when there was none). */
    data class DefaultTemplateChosen(val templateId: String, val previous: String?)

    class DefaultTemplateState {
        /** The default template of the kind: the truth a query reads. */
        var templateId: String? = null
            private set

        fun onSourcing(event: DefaultTemplateChosen) {
            templateId = event.templateId
        }
    }

    /** One per tenant and kind: its id is the kind, and its tenant the templates'. */
    @AggregateRoot(ownership = Ownership.TENANT)
    class DefaultTemplate(private val state: DefaultTemplateState) {
        fun onCommand(command: ChooseDefaultTemplate): DefaultTemplateChosen? =
            if (command.templateId == state.templateId) {
                null
            } else {
                DefaultTemplateChosen(command.templateId, previous = state.templateId)
            }
    }
    // endregion

    // region saga
    /** A command of the template aggregate: whether the template is its kind's default, as its own state shows it. */
    data class MarkDefault(val isDefault: Boolean)

    /** Lets the new default and the one it replaced follow the slot. */
    @Saga
    class DefaultTemplateSaga {
        @OnEvent
        fun onDefaultTemplateChosen(
            event: StateTypedEvent<DefaultTemplateState, DefaultTemplateChosen>,
        ): List<CommandRequest<MarkDefault>> {
            val tenantId = event.ref.tenantId
            return listOfNotNull(
                MarkDefault(true).toCommandRequest(to = TEMPLATE.ref(tenantId, event.event.templateId)),
                event.event.previous?.let { MarkDefault(false).toCommandRequest(to = TEMPLATE.ref(tenantId, it)) },
            )
        }

        companion object {
            /**
             * The templates' aggregate type. In a service the api's contract names it for the command's class, and
             * `toCommandRequest(templateId)` is enough.
             */
            val TEMPLATE = AggregateType("docs", "template")
        }
    }
    // endregion

    private val slot = aggregate<DefaultTemplateState>("docs", "default-template") {
        initial { DefaultTemplateState() }
        decide<ChooseDefaultTemplate>(Creation.ALLOWED) { state, body ->
            DefaultTemplate(state).onCommand(body)?.let { Decision.events(it) } ?: Decision.NoOp
        }
        aggregateId<ChooseDefaultTemplate> { it.kind }
        evolve<DefaultTemplateChosen> { onSourcing(it) }
    }

    private val saga = DefaultTemplateSaga()

    private val sagaBindings = listOf(
        ProcessorBinding.saga(
            "DefaultTemplateSaga",
            "onDefaultTemplateChosen",
            DefaultTemplateState::class.java,
            derives = listOf(MarkDefault::class.java),
            eventType = DefaultTemplateChosen::class.java,
        ) { stream, context ->
            eachEvent(stream, DefaultTemplateChosen::class.java, context) { event, _ ->
                saga.onDefaultTemplateChosen(event)
            }.flatten()
        },
    )

    @Test
    fun `choosing a default changes the slot's holder in one commit`() {
        // region slot-spec
        aggregateSpec(slot, id = "invoice") {
            given(DefaultTemplateChosen("template-1", previous = null))
            whenCommand(ChooseDefaultTemplate("invoice", "template-2")) {
                expectEvents(DefaultTemplateChosen("template-2", previous = "template-1"))
                expectState { templateId.assert().isEqualTo("template-2") }
            }
            // Choosing the default again changes nothing.
            whenCommand(ChooseDefaultTemplate("invoice", "template-2")) { expectUnchanged() }
        }
        // endregion
    }

    @Test
    fun `the first choice creates the slot`() {
        aggregateSpec(slot, id = "invoice") {
            whenCommand(ChooseDefaultTemplate("invoice", "template-1")) {
                expectEvents(DefaultTemplateChosen("template-1", previous = null))
            }
        }
    }

    @Test
    fun `the saga marks the new default and unmarks the one it replaced`() {
        // region saga-spec
        sagaSpec(sagaBindings) {
            givenAggregate(slot, "invoice", DefaultTemplateChosen("template-1", previous = null))
            whenEvent(DefaultTemplateChosen("template-2", previous = "template-1")) {
                expectCommands(
                    DefaultTemplateSaga.TEMPLATE.ref(DEFAULT_TENANT, "template-2") to MarkDefault(true),
                    DefaultTemplateSaga.TEMPLATE.ref(DEFAULT_TENANT, "template-1") to MarkDefault(false),
                )
            }
        }
        // endregion
    }
}
