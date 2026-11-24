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

import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin

/**
 * Whether this declaration, or one it is nested in, is `internal`: visible in its module alone. A generated
 * declaration that names it is `internal` too, and the module's own code still sees it.
 */
internal fun KSDeclaration.isModuleInternal(): Boolean =
    generateSequence(this) { it.parentDeclaration }.any { Modifier.INTERNAL in it.modifiers }

/**
 * Whether this is a Kotlin `protected` declaration: out of reach of the generated code, which calls it from outside its
 * class. A Java `protected` member is visible in its package, where the generated code is.
 */
internal fun KSDeclaration.isKotlinProtected(): Boolean =
    Modifier.PROTECTED in modifiers && origin != Origin.JAVA && origin != Origin.JAVA_LIB

/** Whether this type, or one of its type arguments, names a declaration that is internal to its module. */
internal fun KSType.namesModuleInternal(): Boolean =
    declaration.isModuleInternal() || arguments.any { it.type?.resolve()?.namesModuleInternal() == true }
