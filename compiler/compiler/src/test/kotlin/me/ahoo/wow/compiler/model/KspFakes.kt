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

package me.ahoo.wow.compiler.model

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSValueArgument
import java.lang.reflect.Proxy

/**
 * A `KSAnnotation` with the [given] arguments and the [default] ones, by name, as KSP lists them; nothing else of it is
 * read.
 */
fun fakeAnnotation(
    vararg given: Pair<String, Any?>,
    default: List<Pair<String, Any?>> = emptyList(),
): KSAnnotation = fake { method ->
    when (method) {
        "getArguments" -> given.map(::fakeArgument)
        "getDefaultArguments" -> default.map(::fakeArgument)
        else -> error("A KSAnnotation's $method is not read.")
    }
}

/** A `KSName` that is [name]. */
fun fakeName(name: String): KSName = fake { if (it == "asString") name else error("A KSName's $it is not read.") }

private fun fakeArgument(entry: Pair<String, Any?>): KSValueArgument = fake { method ->
    when (method) {
        "getName" -> fakeName(entry.first)
        "getValue" -> entry.second
        else -> error("A KSValueArgument's $method is not read.")
    }
}

/** An instance of the interface [T] whose every method answers [answer] of its name. */
inline fun <reified T : Any> fake(crossinline answer: (String) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        answer(method.name)
    } as T
