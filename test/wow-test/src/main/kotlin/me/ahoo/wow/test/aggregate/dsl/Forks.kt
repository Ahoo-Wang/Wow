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

fun <S : Any> ExpectStage<S>.fork(
    displayName: String,
    context: AggregateDslContext<S>,
    verifyError: Boolean,
    block: ForkedVerifiedStageDsl<S>.() -> Unit
): DynamicNode {
    return try {
        val verifiedStage = this.verify().fork(verifyError)
        val forkedVerifiedStageDsl = DefaultForkedVerifiedStageDsl(context, verifiedStage)
        block(forkedVerifiedStageDsl)
        DynamicContainer.dynamicContainer(displayName, forkedVerifiedStageDsl.dynamicNodes)
    } catch (e: Throwable) {
        DynamicTest.dynamicTest("$displayName Error") {
            throw e
        }
    }
}
