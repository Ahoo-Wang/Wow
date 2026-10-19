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

/**
 * The tests a specification run as a tree of tests reports to (decision D245): its steps' tests and its forks'
 * containers, in order.
 */
internal class TestTree {
    val nodes = ArrayList<DynamicNode>()

    /** Runs [action], one step, and adds its test, which reports what it threw; the forks it starts come after it. */
    fun step(name: String, action: () -> Unit) {
        val at = nodes.size
        val failure = failureOf(action)
        nodes.add(at, DynamicTest.dynamicTest(name) { failure?.let { throw it } })
    }

    /** Runs [block], a specification or a fork of one, reporting an exception outside its steps as a test `Error`. */
    fun run(block: () -> Unit) {
        failureOf(block)?.let { failure -> nodes += DynamicTest.dynamicTest("Error") { throw failure } }
    }

    private fun failureOf(action: () -> Unit): Throwable? = try {
        action()
        null
    } catch (failure: AssertionError) {
        failure
    } catch (
        @Suppress("TooGenericExceptionCaught") // Whatever the step throws, its test reports.
        failure: Exception,
    ) {
        failure
    }
}

/** Runs [action], one step of a specification: a test of its own when the specification is a [tree] of tests. */
internal fun step(tree: TestTree?, name: String, action: () -> Unit) {
    if (tree == null) action() else tree.step(name, action)
}

/**
 * Runs [block] on a fork of a specification, named [label] and [path] with the forks around it, made by [fork] to
 * report to the tree it is given: a container of the [tree] the specification reports to, or, without one, a
 * failure that names the fork ([ForkAssertionError]).
 */
internal fun <T> runFork(tree: TestTree?, label: String, path: String, fork: (TestTree?) -> T, block: T.() -> Unit) {
    if (tree != null) {
        val branch = TestTree()
        val spec = fork(branch)
        branch.run { spec.block() }
        tree.nodes += DynamicContainer.dynamicContainer(label, branch.nodes)
        return
    }
    val spec = fork(null)
    try {
        spec.block()
    } catch (failure: ForkAssertionError) {
        throw failure
    } catch (failure: AssertionError) {
        throw ForkAssertionError("In fork \"$path\": ${failure.message}", failure)
    }
}
