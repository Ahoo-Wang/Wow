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

package me.ahoo.wow.test.saga.stateless

/**
 * Interface for defining and executing expectations on saga test results.
 *
 * This interface extends [StatelessSagaExpecter] and provides methods to
 * verify that all accumulated expectations are met by the actual saga results.
 *
 * @param T The type of the saga being tested.
 */
interface ExpectStage<T : Any> : StatelessSagaExpecter<T, ExpectStage<T>> {
    /**
     * Verifies all expectations immediately.
     *
     * This is a convenience method that calls [verify] with immediately = true.
     *
     * @return The expected result after verification.
     */
    fun verify(): ExpectedResult<T> = verify(immediately = true)

    /**
     * Executes the verification logic after completing the test flow orchestration.
     *
     * @param immediately If true, performs verification immediately and throws on failure.
     *                    If false, returns the result without immediate verification.
     * @return The expected result.
     */
    fun verify(immediately: Boolean): ExpectedResult<T>
}
