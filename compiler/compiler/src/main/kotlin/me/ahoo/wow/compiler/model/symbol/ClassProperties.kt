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

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration

/**
 * The properties of this class, then each of its supertypes' own: what a command's `@AggregateId` and
 * `@OccurredAt` and a state's query fields are read from. KSP gives a class its own fields and the non-private ones it
 * inherits, each with its type substituted, while a supertype's private field, which Jackson writes through its
 * public getter, is only among that supertype's own declarations (#6974). A name comes again for each class that
 * declares it, the class's own first.
 */
internal fun KSClassDeclaration.hierarchyProperties(): Sequence<KSPropertyDeclaration> =
    getAllProperties() + getAllSuperTypes()
        .mapNotNull { it.declaration as? KSClassDeclaration }
        .flatMap { it.getDeclaredProperties() }
