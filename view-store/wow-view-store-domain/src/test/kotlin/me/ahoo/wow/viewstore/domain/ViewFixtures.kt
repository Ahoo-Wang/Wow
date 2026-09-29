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

package me.ahoo.wow.viewstore.domain

import me.ahoo.wow.api.messaging.Header
import me.ahoo.wow.command.CommandOperator.withOperator
import me.ahoo.wow.messaging.DefaultHeader
import me.ahoo.wow.serialization.JsonSerializer
import me.ahoo.wow.viewstore.ViewStoreService
import tools.jackson.databind.node.ObjectNode

object ViewFixtures {
    const val APP = "console"
    const val OTHER_APP = "portal"
    const val ALICE = "alice"
    const val BOB = "bob"

    fun appHeader(appId: String = APP, operator: String? = null): Header {
        val header = DefaultHeader.empty().with(ViewStoreService.APP_ID_MESSAGE_HEADER, appId)
        return if (operator == null) header else header.withOperator(operator)
    }

    fun recordConfig(): ObjectNode = JsonSerializer.createObjectNode().put("kind", "record").put("pageSize", 20)

    fun dashboardConfig(vararg references: String): ObjectNode {
        val config = JsonSerializer.createObjectNode().put("kind", "dashboard")
        val panels = config.putArray("panels")
        references.forEachIndexed { index, reference ->
            panels.addObject().put("id", "p$index").put("instanceId", reference)
        }
        panels.addObject().put("id", "markdown").put("kind", "markdown")
        return config
    }
}
