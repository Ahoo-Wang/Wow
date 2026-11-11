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

package me.ahoo.wow.compiler.model.subscription

import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.api.annotation.Blocking
import me.ahoo.wow.api.subscribe.CommandReceipt
import me.ahoo.wow.api.subscribe.SagaContext
import me.ahoo.wow.compiler.model.symbol.binaryName
import me.ahoo.wow.compiler.model.symbol.handlerName
import me.ahoo.wow.compiler.model.symbol.isHandler

/**
 * Reads the receipt handlers of a saga, its `@OnRejected` and `@OnCommitted` functions ([SubscriptionResolver]):
 *
 * - an `@OnRejected` handler is a saga's, takes a `CommandReceipt.Rejected` and, optionally, the
 *   `SagaContext`, returns what a saga's handler returns (`Unit` to accept the rejection), and is not
 *   an `@OnEvent` handler too; no two of a saga take
 *   the rejections of the same command class, or both of every command (`CommandReceipt.Rejected<*>`);
 * - an `@OnCommitted` handler likewise, taking a `CommandReceipt.Committed` instead, no two of a saga the receipts
 *   of the same command class; a handler of both receipts is annotated both and takes a `CommandReceipt`, bound on
 *   both paths.
 */
internal object ReceiptHandlerResolver {
    /** The receipt of either outcome, which a handler of both receipts takes. */
    private val RECEIPT = CommandReceipt::class.java.name
    private const val RECEIPT_NAME = "CommandReceipt"
    private const val BOTH_RECEIPTS = "@OnRejected and @OnCommitted"
    private const val ANY = "kotlin.Any"
    private val SAGA_CONTEXT = SagaContext::class.java.name
    private val SAGA_CONTEXTS = contextsOf(SagaContext::class.java)
    private val BLOCKING = Blocking::class.java.name

    /**
     * The [receipt] handlers [functions] of the processor [name], each a function of one receipt kind only (one of
     * both is reported once, as an `@OnRejected` handler), no two of the same command class.
     */
    fun receiptHandlers(
        className: String,
        name: String,
        receipt: Receipt,
        functions: List<KSFunctionDeclaration>,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
    ): List<ReceiptBinding> {
        val bound = functions.mapNotNull { receiptHandler(it, name, receipt, reactorTypes, problems) }
        bound.groupBy { it.command }.filterValues { it.size > 1 }.forEach { (command, same) ->
            problems += "processor $className has two ${receipt.label} handlers of the ${receipt.nouns} of " +
                "${command ?: "every command (${receipt.parameterName}<*>)"}: ${same.joinToString { it.function }}"
        }
        return bound
    }

    /** A [receipt] handler [function] of the saga [processor]. */
    private fun receiptHandler(
        function: KSFunctionDeclaration,
        processor: String,
        receipt: Receipt,
        reactorTypes: Set<String>,
        problems: MutableList<String>,
    ): ReceiptBinding? {
        val callee = function.simpleName.asString()
        val where = "handler $processor.$callee"
        val count = problems.size
        val name = function.handlerName(where, problems)
        if (function.isPrivate()) {
            problems += "$where is private: the generated subscription calls it"
        }
        val parameters = function.parameters.map { it.type.resolve() }
        val first = parameters.firstOrNull()
        // A handler of both receipts is bound on both paths: each describes it alike, so a problem is reported once.
        val both = Receipt.entries.all { function.isHandler(it.annotation) }
        val label = if (both) BOTH_RECEIPTS else receipt.label
        val parameter = if (both) RECEIPT_NAME else receipt.parameterName
        problems += receiptParameterProblems(where, receipt, both, first?.qualifiedName())
        val context = parameters.getOrNull(1)?.qualifiedName()
        if (context != null && context !in SAGA_CONTEXTS) {
            problems += "$where takes $context: an $label handler takes a $SAGA_CONTEXT, or a context it extends, " +
                "after the receipt, if anything"
        }
        if (parameters.size > 2) {
            problems += "$where takes ${parameters.size} parameters: an $label handler takes ($parameter[, SagaContext])"
        }
        val returned = function.returnType?.resolve()
        val reactor = returned?.qualifiedName() in reactorTypes
        if (returned != null) {
            SubscriptionResolver.sagaReturnProblem(returned, reactor)?.let { problems += "$where $it" }
        }
        val sends = SendsResolver.resolve(function, where, true, null, returned, reactor, problems)
        if (problems.size > count) {
            return null
        }
        val command = commandClass(first!!)
        return ReceiptBinding(
            function = name,
            subscription = "$processor.$name",
            callee = callee,
            command = command,
            commandSource = command?.let { first.arguments.firstOrNull()?.type?.resolve()?.qualifiedName() },
            context = context != null,
            suspending = Modifier.SUSPEND in function.modifiers,
            reactor = reactor,
            blocking = function.isHandler(BLOCKING),
            sends = sends,
            receipt = receipt,
        )
    }

    /**
     * What is wrong with the receipt a [receipt] handler takes, [taken] (its qualified name): a handler of one receipt
     * takes that receipt (`CommandReceipt.Rejected<C>`, `CommandReceipt.Committed<C>`), and a handler of [both], one
     * annotated `@OnRejected` and `@OnCommitted`, takes a `CommandReceipt<C>`: each annotation declares one path, and
     * the parameter's type alone picks none.
     */
    private fun receiptParameterProblems(
        where: String,
        receipt: Receipt,
        both: Boolean,
        taken: String?,
    ): List<String> {
        val other = Receipt.entries.single { it != receipt }
        val problem = when {
            both && taken != RECEIPT ->
                "$where is $BOTH_RECEIPTS, so it takes a $RECEIPT_NAME<C> first, not " +
                    "${taken ?: "nothing"}: a handler of both receipts tells them apart itself"

            both -> null

            taken == RECEIPT ->
                "$where takes a $RECEIPT_NAME<C>, the receipt of either outcome, but is only " +
                    "${receipt.label}: annotate it ${other.label} too, or take a ${receipt.parameterName}<C>"

            taken == other.parameter ->
                "$where is ${receipt.label} but takes a ${other.parameterName}<C>: annotate it " +
                    "${other.label} instead, or take a ${receipt.parameterName}<C>"

            taken != receipt.parameter ->
                "$where must take a ${receipt.parameterName} first, not " +
                    "${taken ?: "nothing"}: an ${receipt.label} handler receives the ${receipt.noun} of a command the saga " +
                    "sent"

            else -> null
        }
        return listOfNotNull(problem)
    }

    /**
     * The binary name of the class [type], a `CommandReceipt<X>`, a `CommandReceipt.Rejected<X>` or a
     * `CommandReceipt.Committed<X>`, takes; null for `*` or `Any`.
     */
    private fun commandClass(type: KSType): String? {
        val argument = type.arguments.firstOrNull()?.type?.resolve() ?: return null
        val declaration = argument.declaration as? KSClassDeclaration ?: return null
        return declaration.takeUnless { it.qualifiedName?.asString() == ANY }?.binaryName()
    }

    private fun KSType.qualifiedName(): String? = declaration.qualifiedName?.asString()
}
