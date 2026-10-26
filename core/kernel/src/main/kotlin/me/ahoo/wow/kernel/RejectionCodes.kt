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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.wow.api.aggregate.AggregateType
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.decide.Rejection
import me.ahoo.wow.api.result.FrameworkCodes

private val logger = KotlinLogging.logger {}

/**
 * Rejection codes as the Manifest, results, receipts and HTTP name them: a decide function may reject with the local
 * name of a code of its aggregate (`invalid-item`, a code without a dot, declared within its `@AggregateRoot` class),
 * which the kernel qualifies with the deciding command's aggregate type (`eshop-basket.basket.invalid-item`) before
 * anything else sees it; a code with a dot is full, and stays as it is.
 *
 * The framework's codes (`wow.*`) carry the framework's meaning: their HTTP status, and whether a client retries
 * them. Only the framework's own commands, whose types are in the `wow.` namespace, decide one; a domain command that
 * rejects with one is answered [FrameworkCodes.COMMAND_FAILED] instead, with its message and the code it chose
 * ([REJECTED_CODE]). A domain's other full codes stay as they are: a shared model's codes (`tree.node-not-found`) are
 * named after the model rather than the context, and the edge looks a code's status up in the command's own context.
 */
@InternalWowApi
object RejectionCodes {
    /** The extension of a reserved code's rejection that holds the code the decide function chose. */
    const val REJECTED_CODE: String = "rejectedCode"

    /** [code] qualified with [type]: `context.aggregate.code` for a local name, [code] itself when it has a dot. */
    @JvmStatic
    fun qualify(code: String, type: AggregateType): String =
        if ('.' in code) code else "${type.context}.${type.name}.$code"

    /**
     * [rejection], decided for a command of [commandType], as the kernel answers it: its code qualified with [type]
     * ([qualify]), or [FrameworkCodes.COMMAND_FAILED] when a domain command chose a framework code; [rejection] itself when
     * its code is full and its own.
     */
    @JvmStatic
    fun qualified(rejection: Rejection, type: AggregateType, commandType: String): Rejection {
        if (rejection.code.startsWith(FrameworkCodes.PREFIX) && !commandType.startsWith(FrameworkCodes.PREFIX)) {
            logger.warn {
                "$type rejected $commandType with ${rejection.code}, a code the framework reserves: answered " +
                    "${FrameworkCodes.COMMAND_FAILED} instead. Reject with a code of the aggregate."
            }
            return Rejection(
                FrameworkCodes.COMMAND_FAILED,
                rejection.message,
                rejection.extensions + (REJECTED_CODE to rejection.code),
            )
        }
        val code = qualify(rejection.code, type)
        return if (code == rejection.code) rejection else Rejection(code, rejection.message, rejection.extensions)
    }
}
