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

package me.ahoo.wow.openapi

import io.swagger.v3.oas.models.info.Info
import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.naming.getContextAlias

object OpenAPIExtensions {
    const val WOW_EXTENSIONS_PREFIX = "x-wow-"
    const val WOW_VERSION = WOW_EXTENSIONS_PREFIX + "version"
    const val WOW_CONTEXT_NAME = WOW_EXTENSIONS_PREFIX + "context-name"
    const val WOW_CONTEXT_ALIAS = WOW_EXTENSIONS_PREFIX + "context-alias"
    fun Info.withExtensions(currentContext: NamedBoundedContext): Info {
        addExtension(WOW_VERSION, Wow.VERSION)
        addExtension(WOW_CONTEXT_NAME, currentContext.contextName)
        addExtension(WOW_CONTEXT_ALIAS, currentContext.getContextAlias())
        return this
    }
}
