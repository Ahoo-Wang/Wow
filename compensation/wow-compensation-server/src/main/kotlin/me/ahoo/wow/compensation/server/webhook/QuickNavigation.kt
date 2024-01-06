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

package me.ahoo.wow.compensation.server.webhook

import me.ahoo.wow.api.exception.RecoverableType
import me.ahoo.wow.compensation.api.ExecutionFailedStatus
import me.ahoo.wow.compensation.api.IExecutionFailedState
import me.ahoo.wow.compensation.server.dashboard.DashboardConfiguration.Companion.NON_RETRYABLE_NAV
import me.ahoo.wow.compensation.server.dashboard.DashboardConfiguration.Companion.SUCCEEDED_NAV
import me.ahoo.wow.compensation.server.dashboard.DashboardConfiguration.Companion.TO_RETRY_NAV
import me.ahoo.wow.compensation.server.dashboard.DashboardConfiguration.Companion.UNRECOVERABLE_NAV

object QuickNavigation {

    fun IExecutionFailedState.toIdNav(host: String): String {
        require(host.isNotBlank()) { "host can not be blank." }
        if (this.recoverable == RecoverableType.UNRECOVERABLE) {
            return buildNav(host, UNRECOVERABLE_NAV, this.id)
        }
        if (this.status == ExecutionFailedStatus.SUCCEEDED) {
            return buildNav(host, SUCCEEDED_NAV, this.id)
        }
        if (!this.isBelowRetryThreshold) {
            return buildNav(host, NON_RETRYABLE_NAV, this.id)
        }
        return buildNav(host, TO_RETRY_NAV, this.id)
    }

    private fun buildNav(host: String, path: String, id: String): String {
        return buildString {
            if (host.endsWith("/")) {
                append(host.substring(0, host.length - 1))
            } else {
                append(host)
            }
            append(path)
            append("?id=")
            append(id)
        }
    }

    fun IExecutionFailedState.toNavAsMarkdown(host: String): String {
        if (host.isBlank()) {
            return "`${this.id}`"
        }
        val nav = this.toIdNav(host)
        return "[${this.id}](${nav})"
    }
}