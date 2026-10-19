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

package me.ahoo.wow.test

import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import java.util.stream.Stream

/** Runs the tests of [this] tree as JUnit would, giving each one's path and what it threw (null when it passed). */
fun Stream<DynamicNode>.results(): List<Pair<String, Throwable?>> {
    val results = ArrayList<Pair<String, Throwable?>>()
    fun visit(node: DynamicNode, parent: String?) {
        val path = parent?.let { "$it/${node.displayName}" } ?: node.displayName
        when (node) {
            is DynamicContainer -> node.children.forEach { visit(it, path) }
            is DynamicTest -> results += path to runCatching { node.executable.execute() }.exceptionOrNull()
        }
    }
    forEach { visit(it, null) }
    return results
}

/** The paths of the tests. */
val List<Pair<String, Throwable?>>.paths: List<String> get() = map { it.first }

/** The paths of the tests that failed. */
val List<Pair<String, Throwable?>>.failed: List<String> get() = filter { it.second != null }.map { it.first }

/** What the test at [path] threw. */
fun List<Pair<String, Throwable?>>.failureOf(path: String): Throwable? = single { it.first == path }.second
