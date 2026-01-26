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

package me.ahoo.wow.apiclient.command

import com.fasterxml.jackson.annotation.JsonIgnore
import me.ahoo.wow.api.modeling.SpaceId
import me.ahoo.wow.command.wait.CommandStage
import me.ahoo.wow.configuration.MetadataSearcher
import java.net.URI

const val COMMAND_SEND_ENDPOINT = "wow/command/send"

data class CommandRequest(
    val body: Any,
    val waitStrategy: WaitStrategy = WaitStrategy(),
    val aggregateId: String? = null,
    val aggregateVersion: Int? = null,
    val tenantId: String? = null,
    val ownerId: String? = null,
    val spaceId: SpaceId? = null,
    val requestId: String? = null,
    val localFirst: Boolean? = null,
    val context: String? = null,
    val aggregate: String? = null,
    val serviceUri: String? = null,
    val type: String? = null
) {

    @get:JsonIgnore
    val commandType: String
        get() = type ?: body::class.java.name

    @get:JsonIgnore
    val serviceId: String by lazy {
        if (!context.isNullOrBlank()) {
            return@lazy context
        }
        return@lazy MetadataSearcher.scopeContext.requiredSearch(commandType).contextName
    }

    @get:JsonIgnore
    val sendUri: URI by lazy {
        val serviceHost = serviceUri ?: "http://$serviceId"
        return@lazy URI.create("$serviceHost/$COMMAND_SEND_ENDPOINT")
    }

    data class WaitStrategy(
        val waitStage: CommandStage = CommandStage.PROCESSED,
        val waitContext: String? = null,
        val waitProcessor: String? = null,
        val waitTimeout: Long? = null
    )
}
