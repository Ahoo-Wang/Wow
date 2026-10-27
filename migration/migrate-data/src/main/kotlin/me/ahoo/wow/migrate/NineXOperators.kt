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

package me.ahoo.wow.migrate

import me.ahoo.wow.api.caller.Operator

/**
 * How the import of a 9.x event stream maps its operator: 9.x keeps the operator in the
 * stream's header [HEADER] (`command_operator`, set by its `CommandOperator`), the principal's id as text; V10 keeps
 * it in its own field, [me.ahoo.wow.spi.log.EventStreamRecord.operator].
 *
 * - The header's value is the operator's [Operator.id]. 9.x records no tenant of the operator, so its
 *   [Operator.tenantId] is the stream's own tenant, and no cross-scope mark, so it is false.
 * - A stream without the header, or with a blank one, an anonymous caller's, has no operator.
 * - The header is a 9.x framework key, never a request fact: the imported stream's headers ([headersOf]) leave it out.
 *
 * A snapshot is rebuilt from the imported log, so its `operator` and `firstOperator` follow from the streams.
 */
internal object NineXOperators {
    /** The 9.x header that holds a stream's operator. */
    const val HEADER: String = "command_operator"

    /** The operator of a 9.x stream of the tenant [tenantId] whose headers are [headers]; null when it has none. */
    fun operatorOf(headers: Map<String, String>, tenantId: String): Operator? =
        headers[HEADER]?.takeIf { it.isNotBlank() }?.let { Operator(it, tenantId) }

    /** [headers] without the operator's header, which the import moves into the stream's operator. */
    fun headersOf(headers: Map<String, String>): Map<String, String> = headers - HEADER
}
