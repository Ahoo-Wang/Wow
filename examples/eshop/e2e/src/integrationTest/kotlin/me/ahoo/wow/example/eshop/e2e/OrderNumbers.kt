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

package me.ahoo.wow.example.eshop.e2e

/**
 * An order's number, which the ordering gives the order a checkout submits and which is the order's id: `SO-`, the day
 * it was issued (`yyMMdd`, UTC), `-`, its sequence (`SO-261031-42`). A checkout that waits for the order's commit in
 * the ordering context, `committed(eshop-ordering)`, is answered with it as the result's aggregate id.
 */
val ORDER_NUMBER = Regex("""SO-\d{6}-\d+""")
