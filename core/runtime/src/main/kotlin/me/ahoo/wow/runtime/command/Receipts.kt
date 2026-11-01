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

package me.ahoo.wow.runtime.command

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.ledger.Ledger
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.spi.wait.WaitContext

/**
 * Hands the receipts of the commands a context's sagas derived to the sagas' handlers,
 * executes what they derive and returns their ids; null when no handler takes the receipt.
 * [me.ahoo.wow.runtime.binding.SagaReceipts] is the annotated sagas'.
 */
@InternalWowApi
interface ReceiptHandler {
    /** Hands [rejected], of a command of the class [command], to the saga's `@OnRejected` handler of it. */
    suspend fun rejected(rejected: CommandReceipt.Rejected<*>, command: Class<*>): List<String>?

    /**
     * Hands [committed], of a command of the class [command], to the saga's `@OnCommitted` handler of it. [wait] is
     * the rest of the command's wait chain past its commit, if any: when its next hop is that handler, the handler's
     * commands carry it on and the hop is signalled, as a saga's hop is.
     */
    suspend fun committed(
        committed: CommandReceipt.Committed<*>,
        command: Class<*>,
        wait: WaitContext? = null
    ): List<String>?
}

/**
 * The receipts of one bounded [context]: the subscription of its receipts topic,
 * `wow.<context>.receipts`, where the contexts its sagas sent commands to wrote why they rejected one, or, when the
 * saga asked for it, that they committed one.
 *
 * Each receipt runs in the lane of the saga's aggregate (the commit its execution handled) and goes to [handler] with
 * the command's class, which the [contracts] give its type name (the schema `#/$defs/<binary name>` of the command)
 * and the [classLoader] loads: the saga's `@OnRejected` handler for it compensates, its `@OnCommitted`
 * handler goes on, and what either derives is executed with ids derived from the command's, so a second execution
 * derives the same requests.
 *
 * - **Deduplicated by command id and kind**: the target may send a receipt more than once (a
 *   crash between the receipt and its checkpoint, or a duplicate request answered again), so a receipt of the same
 *   kind whose command was handled here recently ([REMEMBERED] of them) is dropped; an older copy, after a restart,
 *   reaches the handler again, whose commands the kernel does not decide twice. A commit after a rejection of the same
 *   command is not a copy: the target logs no rejection, so the command sent again was decided
 *   again; the commit reaches the `@OnCommitted` handler, with a warning that the rejection was compensated before.
 * - A receipt no handler takes is logged and passed over: the saga's hop was complete when the command was accepted.
 * - A command type the contracts do not know, a class that cannot be loaded, a handler that throws, or a command it
 *   derives that fails, fails the receipt: retried, then the saga's aggregate parks under the subscription's name, at
 *   the receipt's offset. A rejection the handler meets, of a command it derives or of a call it lets through, parks
 *   it at once.
 */
internal class Receipts internal constructor(
    private val context: String,
    private val contracts: ContractDirectory,
    private val handler: ReceiptHandler,
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: Receipts::class.java.classLoader,
) {
    private val handled = object : LinkedHashMap<String, Boolean>(REMEMBERED, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > REMEMBERED
    }

    /** The subscription of every receipt of the context's receipts topic. */
    val subscription: Subscription = Subscription.messages(nameOf(context), context) { entry ->
        when (val receipt = entry.message) {
            is CommandReceipt.Committed<*> -> handle(receipt, entry.transport?.wait)
            else -> handle(receipt as CommandReceipt.Rejected<*>)
        }
    }

    /** Hands [rejected] to the handler once per command id. */
    internal suspend fun handle(rejected: CommandReceipt.Rejected<*>) = once(rejected.commandId, REJECTED) {
        if (handler.rejected(rejected, commandClassOf(rejected.commandType)) == null) {
            logger.warn {
                "No @OnRejected handler of ${rejected.executionId.subscription} in $context takes the rejection of " +
                    "${rejected.commandType} ${rejected.commandId} on ${rejected.ref}: ${rejected.error.code}."
            }
        }
    }

    /** Hands [committed], with the [wait] its receipt carries, to the handler once per command id. */
    internal suspend fun handle(committed: CommandReceipt.Committed<*>, wait: WaitContext? = null) =
        once(committed.commandId, COMMITTED) {
            if (synchronized(handled) { key(committed.commandId, REJECTED) in handled }) {
                // A rejection is not in the log, so the command, sent again, was decided again.
                logger.warn {
                    "${committed.commandType} ${committed.commandId} on ${committed.ref} committed after its rejection " +
                        "was handled: ${committed.executionId.subscription} in $context compensated a command that " +
                        "then committed."
                }
            }
            if (handler.committed(committed, commandClassOf(committed.commandType), wait) == null) {
                logger.warn {
                    "No @OnCommitted handler of ${committed.executionId.subscription} in $context takes the receipt " +
                        "of ${committed.commandType} ${committed.commandId} on ${committed.ref}."
                }
            }
        }

    /**
     * Runs [handle] unless the receipt of [commandId] of [kind] was handled recently, and remembers it once it was.
     * A commit and a rejection of one command are told apart: a target does not log a rejection,
     * so a command a saga sent again with its id after a rejection is decided again and may commit;
     * the commit then reaches the `@OnCommitted` handler instead of being dropped as a copy of the rejection.
     */
    private suspend fun once(commandId: String, kind: String, handle: suspend () -> Unit) {
        val key = key(commandId, kind)
        if (synchronized(handled) { key in handled }) {
            logger.debug { "Dropping a copy of the $kind receipt of $commandId." }
            return
        }
        handle()
        synchronized(handled) { handled[key] = true }
    }

    private fun key(commandId: String, kind: String): String = "$kind:$commandId"

    /**
     * The class of the command type [commandType] (`ctx.agg.Command`), by its schema in the contracts; a ledger command
     * (`wow.ledger.*`), which no Manifest declares, by its own name: an owning service refuses one back
     * to the ops saga that sent it.
     */
    internal fun commandClassOf(commandType: String): Class<*> {
        Ledger.COMMANDS[commandType]?.let { return it }
        val schema = checkNotNull(contracts.schemaOf(commandType)) {
            "No Manifest of the service or its peers declares the command type $commandType."
        }
        return Class.forName(Manifest.schemaKey(schema), false, classLoader)
    }

    companion object {
        /** How many receipts, by command id and kind, a context remembers handling. */
        const val REMEMBERED: Int = 4_096
        private const val REJECTED = "rejected"
        private const val COMMITTED = "committed"
        private const val LOAD_FACTOR = 0.75f
        private val logger = KotlinLogging.logger {}

        /**
         * The receipts' subscription in [context]: its checkpoints and its parked aggregates are under it, one per
         * context, so two contexts of a service sharing a failure ledger never share an entry.
         */
        fun nameOf(context: String): String = "wow.receipts.$context"
    }
}
