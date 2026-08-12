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

package me.ahoo.wow.query.policy

private val QUERY_POLICY_REASON_CODE_PATTERN = Regex("[A-Z][A-Z0-9_]{0,63}")

class QueryPolicyDeniedException(
    val reasonCode: String
) : RuntimeException("Query policy denied.", null, false, false) {
    init {
        require(QUERY_POLICY_REASON_CODE_PATTERN.matches(reasonCode)) {
            "Query policy denial reason code is invalid."
        }
    }
}
