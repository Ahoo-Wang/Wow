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

package me.ahoo.wow.viewstore.starter.system

import me.ahoo.wow.viewstore.api.SystemView
import me.ahoo.wow.viewstore.domain.view.ViewConfigs
import tools.jackson.databind.node.ObjectNode
import java.security.MessageDigest

object SystemViews {
    private const val REVISION_LENGTH = 16

    /**
     * A system view, checked like a saved one, whose revision is a hash of its content: it changes exactly when the
     * view does.
     */
    fun of(id: String, definitionId: String, title: String, config: ObjectNode): SystemView {
        require(id.isNotBlank()) { "A system view's id must not be blank." }
        require(!id.startsWith(SystemView.CODE_SYSTEM_VIEW_PREFIX)) {
            "A system view's id [$id] must not start with [${SystemView.CODE_SYSTEM_VIEW_PREFIX}]: " +
                "the prefix is kept for the views declared in code."
        }
        val kind = ViewConfigs.requireValid(config)
        val checkedTitle = ViewConfigs.requireTitle(title)
        ViewConfigs.requireDefinitionId(definitionId)
        return SystemView(
            id = id,
            definitionId = definitionId,
            title = checkedTitle,
            kind = kind,
            revision = revision(definitionId, checkedTitle, config),
            config = config,
        )
    }

    private fun revision(definitionId: String, title: String, config: ObjectNode): String {
        val content = listOf(definitionId, title, config.toString()).joinToString("\u0000")
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(REVISION_LENGTH)
    }
}
