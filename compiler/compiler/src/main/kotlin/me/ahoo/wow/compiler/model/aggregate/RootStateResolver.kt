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

package me.ahoo.wow.compiler.model.aggregate

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import me.ahoo.wow.compiler.model.symbol.ModelNames.AFTER_COMMAND_ANNOTATION
import me.ahoo.wow.compiler.model.symbol.ModelNames.ON_COMMAND_ANNOTATION
import me.ahoo.wow.compiler.model.symbol.ModelNames.ON_SOURCING
import me.ahoo.wow.compiler.model.symbol.ModelNames.ON_SOURCING_ANNOTATION
import me.ahoo.wow.compiler.model.symbol.annotatedIn

/** Which class is an aggregate root's state, and the handlers declared on the wrong one of the two. */
internal object RootStateResolver {
    /**
     * The root's state, decided explicitly, not from the order or the defaults of its constructors: a
     * root is built from its state when one of its constructors takes exactly one parameter whose class has sourcing
     * functions (`@OnSourcing`, or named `onSourcing`), and that class is the state; the root may have other
     * constructors, such as a Java no-argument one, and the parameter may have a default value. Else the root is its
     * own state. Two such constructors of different states are a problem, and so is a constructor
     * whose first parameter is a state but that takes more.
     */
    fun stateOf(root: KSClassDeclaration, problems: MutableList<String> = mutableListOf()): KSClassDeclaration {
        val constructors = root.getConstructors().toList()
        fun KSValueParameter.stateClass(): KSClassDeclaration? =
            (type.resolve().declaration as? KSClassDeclaration)?.takeIf { it != root && it.hasSourcingFunctions() }
        val states = constructors.filter { it.parameters.size == 1 }.mapNotNull { it.parameters.single().stateClass() }
            .distinct()
        if (states.size > 1) {
            problems += "the root's constructors take the states ${states.joinToString { it.simpleName.asString() }}: " +
                "a root is built from one state, so keep one constructor taking it"
        }
        val state = states.firstOrNull()
        if (state == null) {
            constructors.firstOrNull { it.parameters.size > 1 && it.parameters.first().stateClass() != null }?.let {
                problems += "the root's constructor must take only its state"
            }
        }
        return state ?: root
    }

    /** Whether this class, or a superclass, has a sourcing function: what makes it an aggregate's state. */
    fun KSClassDeclaration.hasSourcingFunctions(): Boolean = getAllFunctions().any {
        it.simpleName.asString() == ON_SOURCING || it.annotatedIn(ON_SOURCING_ANNOTATION) != null
    }

    /**
     * Whether this class, or one it is nested in, is `internal`: the generated factory exposes the state in its
     * signature, so it is `internal` too, and the module's model provider still calls it.
     */
    fun KSClassDeclaration.isInternal(): Boolean =
        generateSequence<KSDeclaration>(this) { it.parentDeclaration }.any { Modifier.INTERNAL in it.modifiers }

    /**
     * The handler annotations on the wrong class of an aggregate whose root is built from its state:
     * command functions and after hooks are the root's, sourcing functions the state's. The generated model would not
     * see them.
     */
    fun misplacedProblems(root: KSClassDeclaration, state: KSClassDeclaration): List<String> {
        fun annotated(type: KSClassDeclaration, annotation: String): List<String> =
            type.getAllFunctions().filter { it.annotatedIn(annotation) != null }.map { it.simpleName.asString() }
                .toList()
        val rootName = root.simpleName.asString()
        val stateName = state.simpleName.asString()
        return annotated(state, ON_COMMAND_ANNOTATION).map {
            "the state $stateName's function $it is annotated @OnCommand: command functions are the aggregate " +
                "root's, so move it to $rootName"
        } + annotated(state, AFTER_COMMAND_ANNOTATION).map {
            "the state $stateName's function $it is annotated @AfterCommand: after hooks are the aggregate root's, " +
                "so move it to $rootName"
        } + annotated(root, ON_SOURCING_ANNOTATION).map {
            "the aggregate root $rootName's function $it is annotated @OnSourcing: sourcing functions are the " +
                "state's, so move it to $stateName"
        }
    }
}
