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

package me.ahoo.wow.spring.boot.starter.elasticsearch

import me.ahoo.wow.api.Wow
import me.ahoo.wow.api.naming.EnabledCapable
import me.ahoo.wow.elasticsearch.ElasticsearchIndexNaming
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = ElasticsearchProperties.PREFIX)
class ElasticsearchProperties(
    override var enabled: Boolean = true,
    var autoInitTemplate: Boolean = true,
    var compatibilityVersion: Int? = null,
    /**
     * Put before every index, index template and index pattern Wow names on the cluster
     * (`{indexPrefix}wow.{context}.{aggregate}.snapshot`, `{indexPrefix}wow-snapshot-template`, …), so that several
     * deployments share one cluster. Unset or blank, the names are Wow's own. It must be a valid start of an index
     * name (lowercase, none of `\ / * ? " < > | , # :` or whitespace, not starting with `-`, `_`, `+`, `.` or
     * `wow.`); an invalid one fails startup.
     */
    var indexPrefix: String? = null,
) : EnabledCapable {
    /** The constructor from before the index prefix, kept for binary compatibility. */
    constructor(
        enabled: Boolean = true,
        autoInitTemplate: Boolean = true,
        compatibilityVersion: Int? = null,
    ) : this(enabled, autoInitTemplate, compatibilityVersion, null)

    /** The index naming [indexPrefix] gives; fails on an invalid prefix. */
    fun toIndexNaming(): ElasticsearchIndexNaming {
        val prefix = indexPrefix?.trim().orEmpty()
        return if (prefix.isEmpty()) ElasticsearchIndexNaming.DEFAULT else ElasticsearchIndexNaming(prefix)
    }

    companion object {
        const val PREFIX = "${Wow.WOW_PREFIX}elasticsearch"
        const val COMPATIBILITY_VERSION_KEY = "$PREFIX.compatibility-version"
    }
}
