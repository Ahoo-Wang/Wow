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

package me.ahoo.wow.kernel

import me.ahoo.wow.api.aggregate.Scope
import me.ahoo.wow.api.annotation.InternalWowApi
import me.ahoo.wow.api.caller.Caller
import me.ahoo.wow.api.caller.Operator
import me.ahoo.wow.api.caller.TargetScope
import me.ahoo.wow.api.decide.RequestFacts

/**
 * What a command records of the caller that sends it, as admission derives it: the [scope] it acts in, the
 * [operator] that sends it and the request [facts] of the caller, its application's among them. The command gateway,
 * the edge and the test DSL derive them alike ([of]); the tenant is [me.ahoo.wow.spi.tenant.TenantPolicy]'s.
 */
@InternalWowApi
class CallerAdmission private constructor(
    /** The target's scope when there is one, else the caller's. */
    val scope: Scope,
    /** The caller's operator, acting elsewhere when there is a target; null for an anonymous caller. */
    val operator: Operator?,
    /** The caller's request facts: its application as [RequestFacts.APP_ID] when it has one. */
    val facts: Map<String, String>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || other is CallerAdmission && scope == other.scope && operator == other.operator &&
            facts == other.facts

    override fun hashCode(): Int = listOf(scope, operator, facts).hashCode()

    override fun toString(): String = "CallerAdmission(scope=$scope, operator=$operator, facts=$facts)"

    @InternalWowApi
    companion object {
        /**
         * The admission of a command of [caller], acting in [target] when given (a crossing `Wow-Scope`, a call's
         * root), else in its own scope.
         */
        @JvmStatic
        @JvmOverloads
        fun of(caller: Caller, target: TargetScope? = null): CallerAdmission = CallerAdmission(
            scope = target?.scope ?: caller.scope,
            operator = caller.toOperator(actingElsewhere = target != null),
            facts = factsOf(caller),
        )

        /** The request facts of [caller]: its application as [RequestFacts.APP_ID] when it has one. */
        @JvmStatic
        fun factsOf(caller: Caller): Map<String, String> =
            caller.app?.let { mapOf(RequestFacts.APP_ID to it.trim()) }.orEmpty()
    }
}
