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

@file:JvmName("ClassNames")

package me.ahoo.wow.manifest

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * The schema keyword naming the JVM class a value is written from, by its binary name: an api module's generator reads
 * it from the contract to reference the class or copy its structure into a state view. It is the service's internals,
 * so the public port's documents leave it out ([withoutClassNames]); the contract and the internal port keep it.
 */
const val CLASS_KEYWORD: String = "x-wow-class"

/** This Manifest without [CLASS_KEYWORD] in any schema of its `$defs`: what the public port serves. */
@InternalWowApi
fun Manifest.withoutClassNames(): Manifest =
    copyWith(defs = defs.mapValues { (_, schema) -> withoutClassNames(schema) })

private fun withoutClassNames(node: Any?): Any? = when (node) {
    is Map<*, *> -> node.filterKeys { it != CLASS_KEYWORD }.mapValues { (_, value) -> withoutClassNames(value) }
    is List<*> -> node.map(::withoutClassNames)
    else -> node
}
