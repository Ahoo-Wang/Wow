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
import me.ahoo.wow.api.subscribe.CommandRejected
import me.ahoo.wow.manifest.Manifest
import me.ahoo.wow.runtime.contract.ContractDirectory
import me.ahoo.wow.runtime.subscription.Subscription

/**
 * Hands the rejection of a command a context's saga derived to the saga's `@OnRejected` handler for the command's
 * class (decisions D136, D153), executes what it derives to compensate and returns their ids; null when no handler
 * takes it. [me.ahoo.wow.runtime.binding.SagaRejections] is the annotated sagas'.
 */
fun interface RejectionHandler {
    suspend fun handle(rejected: CommandRejected<*>, command: Class<*>): List<String>?
}

/**
 * The receipts of one bounded [context] (decisions D47, D136): the subscription of its receipts topic,
 * `wow.<context>.receipts`, where the contexts that rejected a command one of its sagas sent wrote why.
 *
 * Each receipt runs in the lane of the saga's aggregate (the commit its execution handled) and goes to [handler] with
 * the rejected command's class, which the [contracts] give its type name (the schema `#/$defs/<binary name>` of the
 * command, decision D157) and the [classLoader] loads: the saga's `@OnRejected` handler for it compensates, and what it
 * derives is executed with ids derived from the rejected command's, so a second execution derives the same requests.
 *
 * - **Deduplicated by command id** (decision D136): the target may send a receipt more than once (a crash between the
 *   receipt and its checkpoint), so a receipt whose command was handled here recently ([REMEMBERED] of them) is
 *   dropped; an older copy, after a restart, reaches the handler again, whose compensations the kernel does not decide
 *   twice.
 * - A receipt no handler takes is logged and passed over: the saga's hop was complete when the command was accepted.
 * - A command type the contracts do not know, a class that cannot be loaded, a handler that throws, or a compensation
 *   that fails or is rejected in turn, fails the receipt: retried, then the saga's aggregate parks under the
 *   subscription's name, at the receipt's offset.
 */
class Receipts internal constructor(
    private val context: String,
    private val contracts: ContractDirectory,
    private val handler: RejectionHandler,
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: Receipts::class.java.classLoader,
) {
    private val handled = object : LinkedHashMap<String, Boolean>(REMEMBERED, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > REMEMBERED
    }

    /** The subscription of every receipt of the context's receipts topic. */
    val subscription: Subscription = Subscription.messages(nameOf(context), context) { entry ->
        handle(entry.message as CommandRejected<*>)
    }

    /** Hands [rejected] to the handler once per command id. */
    internal suspend fun handle(rejected: CommandRejected<*>) {
        if (synchronized(handled) { rejected.commandId in handled }) {
            logger.debug { "Dropping a copy of the receipt of ${rejected.commandId}." }
            return
        }
        val derived = handler.handle(rejected, commandClassOf(rejected.commandType))
        if (derived == null) {
            logger.warn {
                "No @OnRejected handler of ${rejected.executionId.subscription} in $context takes the rejection of " +
                    "${rejected.commandType} ${rejected.commandId} on ${rejected.ref}: ${rejected.error.code}."
            }
        }
        synchronized(handled) { handled[rejected.commandId] = true }
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
