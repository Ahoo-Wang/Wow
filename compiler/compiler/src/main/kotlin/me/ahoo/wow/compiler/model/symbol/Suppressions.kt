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

import com.google.devtools.ksp.symbol.KSAnnotated

private const val NAMES = "names"

/**
 * Whether this declaration is annotated `@Suppress` naming [name]. The annotation is kept only in the source, which
 * is what the processor reads. A `@Suppress` without a value is one of [problems] ([argumentsOf]).
 */
internal fun KSAnnotated.suppresses(name: String, problems: MutableList<String>): Boolean =
    argumentsOf(Suppress::class, problems)?.strings(NAMES)?.contains(name) == true
