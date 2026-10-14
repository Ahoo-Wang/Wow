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

package me.ahoo.wow.platform.viewstore.server.system

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The view store service's settings (`wow.view-store`, the 9.x starter's prefix).
 *
 * @property systemViews the read-only system views the service serves ([ConfiguredSystemViews]).
 */
@ConfigurationProperties(prefix = ViewStoreProperties.PREFIX)
class ViewStoreProperties(
    var systemViews: MutableList<SystemViewProperties> = mutableListOf(),
) {
    companion object {
        const val PREFIX: String = "wow.view-store"
    }
}

/**
 * One configured system view, as 9.x configured it.
 *
 * @property tenantId the tenant it is for; blank is every tenant.
 * @property appId the application it is for; blank is every application.
 * @property id its id, which does not start with `system:`.
 * @property definitionId the definition it is a view of.
 * @property title its title.
 * @property config the engine's `ViewConfig` as JSON text.
 */
class SystemViewProperties(
    var tenantId: String = "",
    var appId: String = "",
    var id: String = "",
    var definitionId: String = "",
    var title: String = "",
    var config: String = "",
)
