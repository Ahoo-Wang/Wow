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

package me.ahoo.wow.manifest.tools

import me.ahoo.wow.api.naming.NameRules
import me.ahoo.wow.manifest.ContextManifest

/**
 * The contract generator's defence for a Manifest that did not come from the Wow compiler. The compiler
 * refuses a name or a rejection code that breaks the rules of [NameRules], so the generator, which takes an object, a
 * constant or a hop from every one of them, meets one only in a Manifest something else wrote. It refuses that
 * Manifest naming the entry: no identifier is guessed for a name none comes from, and no exception leaves without one
 * (a code ending in a dot once ended the generation with `NoSuchElementException`).
 */
internal object ContractNames {
    private const val NOT_COMPILED =
        "The Wow compiler refuses it, so this Manifest did not come from one: correct what wrote it."

    /**
     * Fails for the first entry of [context] that breaks its rule: an aggregate's name, a command's or an event's
     * (one of the framework's own apart), each part of a subscription's (`processor.handler`), or a rejection code.
     */
    fun check(context: ContextManifest) {
        val of = "of the context ${context.name}"
        context.aggregates.forEach { aggregate ->
            name("The aggregate \"${aggregate.name}\" $of", aggregate.name)
            val owner = "of the aggregate ${aggregate.name} $of"
            aggregate.commands.forEach { typeName("The command \"${it.name}\" $owner", it.name) }
            aggregate.events.forEach { typeName("The event \"${it.name}\" $owner", it.name) }
        }
        context.commands.forEach { typeName("The command \"${it.name}\" $of", it.name) }
        context.subscriptions.forEach { subscription ->
            val entry = "The subscription \"${subscription.name}\" $of, a processor's name and a handler's joined " +
                "by a dot,"
            subscription.name.split('.').forEach { name(entry, it) }
        }
        context.errors.map { it.code }.forEach { code ->
            NameRules.codeProblem(code)?.let { error("The error code \"$code\" $of $it. $NOT_COMPILED") }
        }
    }

    /** Fails unless [name], the name of [entry], is a name or one of the framework's own. */
    private fun typeName(entry: String, name: String) {
        if (!NameRules.isFrameworkName(name)) {
            name(entry, name)
        }
    }

    /** Fails unless [name], the name of [entry] or a part of it, is a name. */
    private fun name(entry: String, name: String) {
        NameRules.nameProblem(name)?.let { error("$entry $it. $NOT_COMPILED") }
    }
}
