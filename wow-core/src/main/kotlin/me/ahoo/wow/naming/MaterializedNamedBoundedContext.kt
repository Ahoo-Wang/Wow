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

package me.ahoo.wow.naming

import me.ahoo.wow.api.naming.Materialized
import me.ahoo.wow.api.naming.NamedBoundedContext
import me.ahoo.wow.configuration.MetadataSearcher

data class MaterializedNamedBoundedContext(override val contextName: String) : NamedBoundedContext, Materialized

fun String.asNamedBoundedContext(): NamedBoundedContext = MaterializedNamedBoundedContext(this)

fun NamedBoundedContext.getContextAlias(): String {
    val context = MetadataSearcher.metadata.contexts[contextName] ?: return contextName
    return context.alias.ifBlank { contextName }
}
