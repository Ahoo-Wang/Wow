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

/**
 * An exception that enhances test failure reporting by merging orchestration stack traces with execution stack traces.
 *
 * This class is used in dynamic testing scenarios to provide clearer error traces when assertions fail.
 * It combines the stack trace from the test orchestration framework with the actual execution stack trace,
 * making it easier to debug test failures by showing both the test setup and the point of failure.
 *
 * Example usage:
 * ```kotlin
 * val test = captureDynamicTest("My Test") {
 *     assertTrue(false) // This will throw AssertionError
 * }
 * // When the test fails, OrchestrationError will be thrown with merged stack traces
 * ```
 *
 * @param orchestrationStacks The stack trace elements from the test orchestration framework.
 * @param cause The original Throwable that caused the failure, typically an AssertionError.
 */
class OrchestrationError(
    orchestrationStacks: Array<StackTraceElement>,
    cause: Throwable
) : AssertionError(cause) {
    companion object {
        /**
         * Merges orchestration stack traces with execution stack traces for enhanced error reporting.
         *
         * This function filters and combines the stack traces to provide a comprehensive view of the error,
         * skipping irrelevant orchestration frames and adding a separator for clarity.
         *
         * @param orchestrationStacks The stack trace from the test orchestration framework.
         * @param executionStacks The stack trace from the actual test execution.
         * @return A merged array of stack trace elements with orchestration and execution traces combined.
         */
        private fun mergeStacks(
            orchestrationStacks: Array<StackTraceElement>,
            executionStacks: Array<StackTraceElement>
        ): Array<StackTraceElement> {
            val skipOrchestrationStacks =
                orchestrationStacks
                    .asSequence()
                    .filter {
                        !it.className.startsWith("me.ahoo.wow.test") &&
                            !it.className.startsWith("java.lang.")
                    }
                    .take(5)
            val mergedStacks =
                skipOrchestrationStacks +
                    StackTraceElement(
                        "---",
                        "Test Execution Stack",
                        null,
                        -1,
                    ) + executionStacks
            return mergedStacks.toList().toTypedArray()
        }
    }

    init {
        this.stackTrace = mergeStacks(orchestrationStacks, cause.stackTrace)
    }
}

/**
 * Captures a dynamic test and wraps any AssertionError with OrchestrationError for improved stack traces.
 *
 * This function creates a DynamicTest that executes the provided block. If an AssertionError occurs during execution,
 * it is wrapped in an OrchestrationError that includes both the orchestration stack trace and the execution stack trace,
 * aiding in debugging test failures.
 *
 * Example usage:
 * ```kotlin
 * val test = captureDynamicTest("Validate User Creation") {
 *     val user = createUser()
 *     assertNotNull(user.id)
 *     assertEquals("John", user.name)
 * }
 * ```
 *
 * @param displayName The display name for the dynamic test.
 * @param block The test logic to execute, which may throw an AssertionError.
 * @return A DynamicTest instance that can be executed by JUnit.
 * @throws OrchestrationError If an AssertionError occurs during test execution, wrapped with enhanced stack traces.
 */
fun <R> captureDynamicTest(
    displayName: String,
    block: () -> R
): DynamicTest {
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
