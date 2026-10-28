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

package me.ahoo.wow.api.route

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.client.CommandClient

/**
 * A call of a service route ([me.ahoo.wow.api.annotation.ServiceRoute]) as admission let it through: who calls, in
 * which tenant and scope, under which request id, and the commands the handler may send as that caller. A handler
 * sees no HTTP request, as a decide function sees none: it is tested by calling it with a call of its own.
 */
interface RouteCall {
    /** The authenticated caller. */
    val caller: Caller

    /** The tenant the call acts in, as the tenant policy admitted it. */
    val tenantId: String

    /** The scope the call acts in: the caller's own, or the one its `Wow-Scope` targets when it may act elsewhere. */
    val scope: Scope

    /** The request's id (`Wow-Request-Id`), or a new one. */
    val requestId: String

    /** The commands the handler sends as [caller], such as a relay of the caller's command to the service of it. */
    val commands: CommandClient
}
