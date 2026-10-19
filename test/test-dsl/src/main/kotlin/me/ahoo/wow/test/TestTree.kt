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

import org.junit.jupiter.api.DynamicNode

/**
 * The tests a specification run as a tree of tests reports to (decision D245): its steps' tests and its forks'
 * containers, in order.
 */
internal class TestTree {
    val nodes = ArrayList<DynamicNode>()

    /** Runs [action], and gives back what it threw for its test to report; null when it ended. */
    fun failureOf(action: () -> Unit): Throwable? = try {
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
