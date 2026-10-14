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
import me.ahoo.wow.api.subscribe.CommandCommitted
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.subscription.Subscription
import me.ahoo.wow.spi.wait.WaitContext

/**
 * Hands the receipts of the commands a context's sagas derived to the sagas' handlers (decisions D136, D153, D192),
 * executes what they derive and returns their ids; null when no handler takes the receipt.
 * [me.ahoo.wow.runtime.binding.SagaReceipts] is the annotated sagas'.
 */
interface ReceiptHandler {
    /** Hands [rejected], of a command of the class [command], to the saga's `@OnRejected` handler of it. */
    suspend fun rejected(rejected: CommandRejected<*>, command: Class<*>): List<String>?

    /**
     * Hands [committed], of a command of the class [command], to the saga's `@OnCommitted` handler of it. [wait] is
     * the rest of the command's wait chain past its commit, if any: when its next hop is that handler, the handler's
     * commands carry it on and the hop is signalled, as a saga's hop is (decision D192).
     */
    suspend fun committed(committed: CommandCommitted<*>, command: Class<*>, wait: WaitContext? = null): List<String>?
}

/**
 * The receipts of one bounded [context] (decisions D47, D136, D192): the subscription of its receipts topic,
 * `wow.<context>.receipts`, where the contexts its sagas sent commands to wrote why they rejected one, or, when the
 * saga asked for it, that they committed one.
 *
 * Each receipt runs in the lane of the saga's aggregate (the commit its execution handled) and goes to [handler] with
 * the command's class, which the [contracts] give its type name (the schema `#/$defs/<binary name>` of the command,
 * decision D157) and the [classLoader] loads: the saga's `@OnRejected` handler for it compensates, its `@OnCommitted`
 * handler goes on, and what either derives is executed with ids derived from the command's, so a second execution
 * derives the same requests.
 *
 * - **Deduplicated by command id** (decisions D136, D192): the target may send a receipt more than once (a crash
 *   between the receipt and its checkpoint, or a duplicate request answered again), so a receipt whose command was
 *   handled here recently ([REMEMBERED] of them) is dropped; an older copy, after a restart, reaches the handler again,
 *   whose commands the kernel does not decide twice.
 * - A receipt no handler takes is logged and passed over: the saga's hop was complete when the command was accepted.
 * - A command type the contracts do not know, a class that cannot be loaded, a handler that throws, or a command it
 *   derives that fails or is rejected, fails the receipt: retried, then the saga's aggregate parks under the
 *   subscription's name, at the receipt's offset.
 */
class Receipts internal constructor(
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
            is CommandCommitted<*> -> handle(receipt, entry.transport?.wait)
            else -> handle(receipt as CommandRejected<*>)
        }
    }

    /** Hands [rejected] to the handler once per command id. */
    internal suspend fun handle(rejected: CommandRejected<*>) = once(rejected.commandId) {
        if (handler.rejected(rejected, commandClassOf(rejected.commandType)) == null) {
            logger.warn {
                "No @OnRejected handler of ${rejected.executionId.subscription} in $context takes the rejection of " +
                    "${rejected.commandType} ${rejected.commandId} on ${rejected.ref}: ${rejected.error.code}."
            }
        }
    }

    /** Hands [committed], with the [wait] its receipt carries, to the handler once per command id. */
    internal suspend fun handle(committed: CommandCommitted<*>, wait: WaitContext? = null) = once(committed.commandId) {
        if (handler.committed(committed, commandClassOf(committed.commandType), wait) == null) {
            logger.warn {
                "No @OnCommitted handler of ${committed.executionId.subscription} in $context takes the receipt of " +
                    "${committed.commandType} ${committed.commandId} on ${committed.ref}."
            }
        }
    }

    /** Runs [handle] unless the receipt of [commandId] was handled recently, and remembers it once it was. */
    private suspend fun once(commandId: String, handle: suspend () -> Unit) {
        if (synchronized(handled) { commandId in handled }) {
            logger.debug { "Dropping a copy of the receipt of $commandId." }
            return
        }
        handle()
        synchronized(handled) { handled[commandId] = true }
    }

    /** The class of the command type [commandType] (`ctx.agg.Command`), by its schema in the contracts. */
    internal fun commandClassOf(commandType: String): Class<*> {
        val schema = checkNotNull(contracts.schemaOf(commandType)) {
            "No Manifest of the service or its peers declares the command type $commandType."
        }
        return Class.forName(Manifest.schemaKey(schema), false, classLoader)
    }

    companion object {
        /** How many command ids a context remembers handling the receipt of. */
        const val REMEMBERED: Int = 4_096
        private const val LOAD_FACTOR = 0.75f
        private val logger = KotlinLogging.logger {}

        /**
         * The receipts' subscription in [context]: its checkpoints and its parked aggregates are under it, one per
         * context, so two contexts of a service sharing a failure ledger never share an entry.
         */
        fun nameOf(context: String): String = "wow.receipts.$context"
    }
}
