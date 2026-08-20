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

package me.ahoo.wow.mongo.query.backend

import me.ahoo.wow.api.query.expression.QueryValue
import org.bson.conversions.Bson
import java.util.Collections

fun interface MongoNativeQueryTemplate {
    fun build(parameters: Map<String, QueryValue>): Bson
}

class MongoNativeQueryTemplateRegistry @JvmOverloads constructor(
    templates: Map<String, MongoNativeQueryTemplate> = emptyMap()
) {
    private val templates: Map<String, MongoNativeQueryTemplate>

    init {
        val templateIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        val snapshot = LinkedHashMap<String, MongoNativeQueryTemplate>(templates.size)
        templates.forEach { (templateId, template) ->
            require(templateIdPattern.matches(templateId)) { "Mongo native template id is invalid." }
            require(snapshot.put(templateId, template) == null) { "Duplicate Mongo native template id." }
        }
        require(snapshot.size == templates.size) {
            "Mongo native template registry cardinality changed during immutable snapshot."
        }
        this.templates = Collections.unmodifiableMap(snapshot)
    }

    fun template(templateId: String): MongoNativeQueryTemplate? = templates[templateId]

    override fun toString(): String = "MongoNativeQueryTemplateRegistry(templateCount=${templates.size})"
}
