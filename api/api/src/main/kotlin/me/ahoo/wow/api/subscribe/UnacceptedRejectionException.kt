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

package me.ahoo.wow.api.subscribe

import me.ahoo.wow.api.annotation.InternalWowApi

/**
 * A saga's `@OnRejected` handler did not accept [rejected]: its code is none of the [accepted] ones
 * ([CommandReceipt.Rejected.accept]). The handler does not take the rejection, so the runtime answers it as a rejection
 * no handler takes: the saga's aggregate parks at once, without a retry, its reason naming the handler, the command and
 * the code, and a wait at the saga's hop ends REJECTED with the rejection's error.
 *
 * Only `accept` throws it; fields may be added, so the constructor is internal to Wow.
 */
class UnacceptedRejectionException @InternalWowApi constructor(
    val rejected: CommandReceipt.Rejected<*>,
    val accepted: List<String>,
) : RuntimeException(
    "accepts ${accepted.joinToString()}, not the rejection of ${rejected.commandType} ${rejected.commandId} on " +
        "${rejected.ref}: ${rejected.error.code} ${rejected.error.message}",
)
