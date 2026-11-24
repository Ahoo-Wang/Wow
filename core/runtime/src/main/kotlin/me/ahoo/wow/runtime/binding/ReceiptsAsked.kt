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

package me.ahoo.wow.runtime.binding

import me.ahoo.wow.api.decide.Command
import me.ahoo.wow.api.result.FunctionKind
import me.ahoo.wow.api.subscribe.UnrecoverableHandlingException
import me.ahoo.wow.kernel.AggregateModel
import me.ahoo.wow.kernel.ProcessorBinding
import me.ahoo.wow.spi.SystemCommandGroups

/**
 * Whether a context reads the receipts of the commands its sagas send to other contexts, and so has its receipts
 * subscription and topic: a command carries `replyTo` only to a receipts topic its own context has and reads.
 */
internal object ReceiptsAsked {
    /**
     * Whether a saga of [bound] may send a command to a context other than that of [models]: one that declares an
     * `@OnRejected` or `@OnCommitted` handler, or derives or schedules a command class that may be another context's
     * (`@Sends`, else its return type for what it derives). A class may be another context's unless one of [models]
     * decides it as its own: a supertype may, and so may a command every aggregate decides (a lifecycle or a schedule
     * command), which may go to any context's aggregate. A saga that sends only its own context's commands asks for
     * none.
     */
    fun of(bound: Collection<ProcessorBinding<*>>, models: Collection<AggregateModel<*>>): Boolean {
        val own = models.flatMapTo(HashSet()) { model -> model.commandTypes.map { it.type } }
            .filterNot { it in SystemCommandGroups.DECIDED_BY_EVERY_AGGREGATE.classes }
            .toSet()
        return bound.any { saga ->
            saga.kind == FunctionKind.SAGA &&
                (
                    saga.rejections.isNotEmpty() ||
                        saga.commits.isNotEmpty() ||
                        (saga.sends.derives + saga.sends.schedules).any { it !in own }
                    )
        }
    }
}

/**
 * The saga [handler] of [context] derived [command] to another context's aggregate while its context reads no
 * receipts: the command's class is one its own aggregates decide, so nothing told the context it sends that class
 * elsewhere. Declaring an `@OnRejected` handler of the class gives the context its receipts topic.
 */
internal class UnreadReceiptsException(val handler: String, val context: String, val command: Command<*>) :
    UnrecoverableHandlingException(
        "$handler derives ${command.body.javaClass.name} to ${command.ref}, in another context, but context " +
            "$context reads no receipts: its own aggregates decide that class, so it has no receipts topic for the " +
            "command's rejection. Declare an @OnRejected handler of ${command.body.javaClass.simpleName} in " +
            "${handler.substringBefore('.')} (`= rejected.accept(<codes>)`, or `= Unit` to accept every code), so " +
            "the context reads the receipt.",
    )
