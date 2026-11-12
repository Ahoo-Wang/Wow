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

package me.ahoo.wow.query.tck

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.opentest4j.TestAbortedException

/**
 * Runs the case [id] of a contract, [standard] what it requires of a backend. A case the backend declares in
 * [declared] (its `semanticDivergences`) is run all the same: when it fails, as a known divergence of [rule], it is
 * reported skipped as before; when it meets the standard, the declaration is stale, and the case fails, naming it, so
 * a backend's list never keeps a case it no longer diverges on.
 */
internal fun standard(id: String, declared: Set<String>, rule: String, standard: suspend () -> Unit) {
    if (id !in declared) {
        runBlocking { standard() }
        return
    }
    try {
        runBlocking { standard() }
    } catch (aborted: TestAbortedException) {
        throw aborted
    } catch (@Suppress("TooGenericExceptionCaught") diverged: Throwable) {
        Assumptions.abort<Unit>("Known divergence from the $rule: $id.")
    }
    throw AssertionError("[$id] is declared as a divergence, but it meets the standard: remove it from the list.")
}

/** The rule of the filter semantics matrix ([FilterSemantics]). */
internal const val CANONICAL_SEMANTICS = "canonical semantics"

/** The rule of a date bucket's start. */
internal const val BUCKET_RULE = "bucket rule"
