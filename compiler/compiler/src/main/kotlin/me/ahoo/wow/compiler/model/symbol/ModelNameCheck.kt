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

package me.ahoo.wow.compiler.model.symbol

import me.ahoo.wow.api.naming.NameRules
import me.ahoo.wow.compiler.model.aggregate.AliasCheck
import me.ahoo.wow.compiler.model.aggregate.CommandBinding
import me.ahoo.wow.compiler.model.aggregate.SourcingBinding

/**
 * Holds the names the compiler writes into the Manifest to the one rule of a name ([NameRules]): an
 * aggregate's, a command's, an event's, a processor's and a handler's, however it came, written with `@Name` or taken
 * from the class or the function. A name that breaks it is a problem of its declaration, so the build fails there
 * rather than in the contract generated from the Manifest, or in a topic, a route or a wait hop built from the name.
 */
internal object ModelNameCheck {
    /** Where a name comes from when no `@Name` gives it: the declaration's class. */
    const val CLASS = "its class"

    /** Where a handler's name comes from when no `@Name` gives it. */
    const val FUNCTION = "its function"

    /**
     * The problem of the name of [what] (`command PlaceOrder`): the one its `@Name` [declared], else [derived] from
     * [source] ([CLASS] or [FUNCTION]). Null when it is a name. The problem says what to write: a name of the rule's
     * form in the `@Name`, or a `@Name` for a class or a function whose own name gives none.
     */
    fun problem(what: String, declared: String?, derived: String, source: String = CLASS): String? {
        val problem = NameRules.nameProblem(declared ?: derived) ?: return null
        return if (declared != null) {
            "@Name(\"$declared\") of $what $problem. Write a name of that form."
        } else {
            "$what is named \"$derived\" after $source, which $problem. Give it a name of that form with @Name."
        }
    }

    /** The problems of the names of an aggregate's [commands] and of the events of its [sourcing] functions. */
    fun problems(commands: List<CommandBinding>, sourcing: List<SourcingBinding>): List<String> =
        commands.mapNotNull { typeProblem("command", it.commandName, it.type) } +
            sourcing.mapNotNull { typeProblem("event", it.eventName, it.type) }

    /** The problem of the name of the [kind] (`command`, `event`) of the binary name [type], `@Name`d [declared]. */
    private fun typeProblem(kind: String, declared: String?, type: String): String? {
        val simpleName = AliasCheck.simpleName(type)
        return problem("$kind $simpleName", declared, simpleName)
    }
}
