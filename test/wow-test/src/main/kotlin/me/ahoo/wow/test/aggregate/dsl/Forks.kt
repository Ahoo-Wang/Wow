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

package me.ahoo.wow.test.aggregate.dsl

import me.ahoo.wow.test.aggregate.ExpectStage
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest

/**
 * Creates a forked test scenario from the current ExpectStage.
 *
 * This extension function enables branching of test execution from a verified aggregate state,
 * allowing testing of alternative command sequences or error conditions. The fork creates
 * an isolated test context that starts from the verified state of the current stage.
 *
 * When verifyError is true, the fork expects an error to have occurred in the parent stage.
 * When verifyError is false, the fork expects successful execution in the parent stage.
 *
 * @param S the state type of the aggregate being tested
 * @param displayName the name to display for this forked test branch in test reports
 * @param context the DSL context for managing shared state across test stages
 * @param verifyError whether to verify that an error occurred before forking (true) or successful execution (false)
 * @param block the test scenario to execute in the forked context using ForkedVerifiedStageDsl
 * @return a DynamicNode representing the forked test container, or a failed DynamicTest if an exception occurs
 *
 * @throws Throwable if an error occurs during fork setup or execution (wrapped in DynamicTest for reporting)
 *
 * Example usage:
 * ```kotlin
 * expectStage.fork("Test Error Handling", context, verifyError = true) {
 *     whenCommand(ErrorCommand()) {
 *         expectErrorType(IllegalArgumentException::class)
 *     }
 * }
 * ```
 */
fun <S : Any> ExpectStage<S>.fork(
    displayName: String,
    context: AggregateDslContext<S>,
    verifyError: Boolean,
    block: ForkedVerifiedStageDsl<S>.() -> Unit
): DynamicNode =
    try {
        val verifiedStage = this.verify().fork(verifyError)
        val forkedVerifiedStageDsl = DefaultForkedVerifiedStageDsl(context, verifiedStage)
        block(forkedVerifiedStageDsl)
        DynamicContainer.dynamicContainer(displayName, forkedVerifiedStageDsl.dynamicNodes)
    } catch (e: Throwable) {
        DynamicTest.dynamicTest("$displayName Error") {
            throw e
        }
    }
