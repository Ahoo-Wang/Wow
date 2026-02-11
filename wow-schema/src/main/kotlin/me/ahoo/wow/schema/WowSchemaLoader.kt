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

package me.ahoo.wow.schema

import me.ahoo.wow.serialization.toObject
import tools.jackson.databind.node.ObjectNode

object WowSchemaLoader {
    private const val WOW_SCHEMA_PATH_PREFIX = "META-INF/wow-schema/"

    fun loadAsString(resourceName: String): String {
        val resourcePath = "$WOW_SCHEMA_PATH_PREFIX$resourceName.json"
        val resourceURL = this.javaClass.classLoader.getResource(resourcePath)
        requireNotNull(resourceURL) {
            "Can not find wow schema resource: $resourcePath"
        }
        return resourceURL.openStream().use {
            it.readAllBytes().toString(Charsets.UTF_8)
        }
    }

    fun load(resourceName: String): ObjectNode {
        return loadAsString(resourceName).toObject<ObjectNode>()
    }

    fun load(resourceType: Class<*>): ObjectNode {
        return load(resourceType.simpleName)
    }
}
