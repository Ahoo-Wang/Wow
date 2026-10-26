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

package me.ahoo.wow.cosec

import me.ahoo.wow.api.annotation.ExperimentalWowApi
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.runtime.admission.CommandAuthorizer
import me.ahoo.cosec.api.authorization.Authorization as CoSecAuthorization

/**
 * CoSec's decision on a command, as the host makes it: over the application's CoSec policies and the host's default
 * tenant, present when `wow.cosec.authorizer.enabled` is on and the application has CoSec's `Authorization`, whatever
 * other authorizers it has.
 *
 * This is how an application wraps CoSec's decision: its own [CommandAuthorizer] takes this bean, asks [authorizer] for
 * the commands CoSec decides, and decides the others itself. Without the bean, CoSec does not authorize commands here,
 * and the application's authorizer decides alone.
 */
@ExperimentalWowApi
class CoSecAuthorizers @InternalWowApi constructor(
    private val authorization: CoSecAuthorization,
    private val defaultTenant: String,
) {
    /** A command authorizer that asks CoSec's policies ([CoSecCommandAuthorizer]). */
    fun authorizer(): CommandAuthorizer = CoSecCommandAuthorizer(authorization, defaultTenant)
}
