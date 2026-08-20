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

private val QUERY_POLICY_REGISTRATION_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
private val RESERVED_QUERY_POLICY_REGISTRATION_IDS = setOf("system", "combined")

/**
 * Immutable deployment registration for a custom [QueryPolicy].
 *
 * [descriptorId] is a bounded, low-cardinality identifier used for internal observability. It must not contain
 * request data, exception text, or other unbounded values.
 */
class QueryPolicyRegistration(
    val descriptorId: String,
    val order: Int,
    val policy: QueryPolicy
) {
    init {
        require(QUERY_POLICY_REGISTRATION_ID_PATTERN.matches(descriptorId)) {
            "Query policy registration descriptor id is invalid."
        }
        require(descriptorId !in RESERVED_QUERY_POLICY_REGISTRATION_IDS) {
            "Query policy registration descriptor id is reserved."
        }
    }

    override fun toString(): String =
        "QueryPolicyRegistration(descriptorId=$descriptorId, order=$order, policy=<redacted>)"
}
