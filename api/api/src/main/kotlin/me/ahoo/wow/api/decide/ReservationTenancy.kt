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
 * Among which aggregates a reserved key is unique ([DecideScope.reserve]); always among the aggregates of one type
 * (decision D307).
 */
enum class ReservationTenancy {
    /** Unique within the aggregate's tenant: two tenants may each hold the key (axiom A6). The default. */
    TENANT,

    /** Unique across the tenants of the aggregate type, such as a global login name: say so explicitly. */
    SYSTEM,
}
