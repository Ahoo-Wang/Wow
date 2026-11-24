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

package me.ahoo.wow.runtime.subscription

import me.ahoo.wow.kernel.FailureClassification
import me.ahoo.wow.kernel.FailureReasons
import me.ahoo.wow.runtime.CommandBusyException
import me.ahoo.wow.runtime.binding.UnreadReceiptsException
import me.ahoo.wow.spi.subscription.FailureClass
import me.ahoo.wow.spi.subscription.FailureClassifier
import me.ahoo.wow.spi.subscription.FailureDescriber
import me.ahoo.wow.spi.subscription.FailureDescription

/**
 * How the subscriptions of a runtime classify their handlers' failures: the kernel's classification
 * ([FailureClassification], the one the test DSL classifies with too), the application's classifiers first, then the
 * modules' on the classpath ([FailureClassification.MODULES]), then what only the runtime throws ([ExecutorFailures]),
 * then the built-in rules.
 */
internal object RuntimeFailures {
    /** The runtime's own classification, with no application classifier or describer. */
    val DEFAULT: FailureClassification by lazy { of() }

    /**
     * The [application]'s classifiers, then the modules', then the runtime's own; and the reasons of the
     * [describers]' descriptions, then the modules', then the runtime's own ([RuntimeDescriptions]).
     */
    fun of(
        application: List<FailureClassifier> = emptyList(),
        describers: List<FailureDescriber> = emptyList(),
    ): FailureClassification = FailureClassification.of(
        application,
        own = listOf(ExecutorFailures),
        reasons = FailureReasons.of(describers, own = listOf(RuntimeDescriptions)),
    )
}

/** The words of the runtime's descriptions. */
internal enum class RuntimePhrase {
    NOT_DECODABLE_BY_THIS_VERSION,
    DERIVES,
    TO_CONTEXT,
    BUT_ITS_CONTEXT_READS_NO_RECEIPTS,
    DECLARE_A_REJECTION_HANDLER_OF,
}

/**
 * What only the runtime throws, described: a message of a topic this version cannot decode
 * ([UndecodableEntryException]), what it could not decode being its cause's; and a saga's command to another context
 * from a context that reads no receipts ([UnreadReceiptsException]), by the handler, the command's class and its
 * target's context.
 */
internal object RuntimeDescriptions : FailureDescriber {
    override fun describe(failure: Throwable): FailureDescription? = when (failure) {
        is UndecodableEntryException ->
            FailureDescription.of(FailureDescription.phrase(RuntimePhrase.NOT_DECODABLE_BY_THIS_VERSION))

        is UnreadReceiptsException -> {
            val command = FailureDescription.type(failure.command.body.javaClass)
            FailureDescription.of(
                FailureDescription.name(failure.handler),
                FailureDescription.phrase(RuntimePhrase.DERIVES),
                command,
                FailureDescription.phrase(RuntimePhrase.TO_CONTEXT),
                FailureDescription.name(failure.command.ref.type.context),
                FailureDescription.phrase(RuntimePhrase.BUT_ITS_CONTEXT_READS_NO_RECEIPTS),
                FailureDescription.phrase(RuntimePhrase.DECLARE_A_REJECTION_HANDLER_OF),
                command,
            )
        }

        else -> null
    }
}

/**
 * What only the runtime throws: a command the node's own executor refused as busy ([CommandBusyException]) is
 * [FailureClass.BACKPRESSURE], a downstream that is up but overloaded, as a store that did not admit a call is.
 */
internal object ExecutorFailures : FailureClassifier {
    override fun classify(failure: Throwable): FailureClass? =
        FailureClass.BACKPRESSURE.takeIf { failure is CommandBusyException }
}
