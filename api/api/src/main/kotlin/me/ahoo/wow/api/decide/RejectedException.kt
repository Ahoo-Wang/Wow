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

package me.ahoo.wow.api.decide

/**
 * A decide function's refusal thrown instead of returned: the kernel answers it exactly as the
 * [Decision.Rejected] of [rejection], so nothing is appended, a compensation runs as for a returned rejection, and the
 * caller gets the same answer. Kotlin throws one with `reject(code, message)` (or [DecideScope.reject]); Java writes
 * `throw new RejectedException(Rejection.of("out-of-stock", "Only 2 left."))`, and a handler may rethrow one.
 *
 * It carries no stack trace: it is an answer, not an error. Its message is the rejection's, which the caller reads.
 */
class RejectedException(val rejection: Rejection) : RuntimeException(rejection.message) {
    override fun fillInStackTrace(): Throwable = this
}
