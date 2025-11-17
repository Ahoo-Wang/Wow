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

import org.junit.jupiter.api.DynamicTest

class OrchestrationError(orchestrationStacks: Array<StackTraceElement>, cause: Throwable) :
    AssertionError(cause) {
    companion object {
        private fun mergeStacks(
            orchestrationStacks: Array<StackTraceElement>,
            executionStacks: Array<StackTraceElement>
        ): Array<StackTraceElement> {
            val skipOrchestrationStacks = orchestrationStacks.asSequence().dropWhile {
                it.methodName !== "captureDynamicTest"
            }.drop(1).take(8)
            val mergedStacks = skipOrchestrationStacks + StackTraceElement(
                "---",
                "Test Execution Stack",
                null,
                -1
            ) + executionStacks
            return mergedStacks.toList().toTypedArray()
        }
    }

    init {
        this.stackTrace = mergeStacks(orchestrationStacks, cause.stackTrace)
    }
}

fun <R> captureDynamicTest(displayName: String, block: () -> R): DynamicTest {
    val orchestrationStacks = Thread.currentThread().stackTrace
    return DynamicTest.dynamicTest(displayName) {
        try {
            block()
        } catch (e: AssertionError) {
            val orchestrationError = OrchestrationError(orchestrationStacks, e)
            throw orchestrationError
        }
    }
}
