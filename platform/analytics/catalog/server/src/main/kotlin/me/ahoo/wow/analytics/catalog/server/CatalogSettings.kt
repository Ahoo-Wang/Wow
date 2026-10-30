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

package me.ahoo.wow.analytics.catalog.server

import me.ahoo.wow.analytics.catalog.domain.warehouse.WarehouseCatalog
import me.ahoo.wow.api.annotation.RouteMethod
import me.ahoo.wow.api.annotation.ServiceRoute
import me.ahoo.wow.api.route.RouteCall

/**
 * What a console asks of the catalog it works with, so it offers what this catalog does: the warehouse
 * [connection] a source's id starts with, whether a source needs an approver before datasets read it
 * ([requireSourceApproval]) and a publication one before queries read it ([requirePublicationApproval]), and
 * whether the catalog's roles decide who sends what ([rolesDecide], [AnalyticsCatalogAuthorizer]) or CoSec's
 * policies do, which a console cannot read ahead.
 */
data class CatalogSettings(
    val connection: String,
    val requireSourceApproval: Boolean,
    val requirePublicationApproval: Boolean,
    val rolesDecide: Boolean,
)

/** The catalog's [CatalogSettings], a service route of the context: `GET /analytics-catalog/settings`. */
class CatalogSettingsRoute(private val settings: CatalogSettings) {
    /** The catalog's settings, as any caller of it reads them. */
    @Suppress("UnusedParameter") // The route's call: nothing of it decides the answer.
    @ServiceRoute(method = RouteMethod.GET, path = "settings")
    suspend fun settings(call: RouteCall): CatalogSettings = settings

    companion object {
        /**
         * The settings of the catalog over [catalog], configured by [properties], whose commands [rolesDecide] by the
         * catalog's roles or not.
         */
        fun of(
            catalog: WarehouseCatalog,
            properties: AnalyticsCatalogProperties,
            rolesDecide: Boolean,
        ): CatalogSettingsRoute =
            CatalogSettingsRoute(
                CatalogSettings(
                    connection = catalog.connection,
                    requireSourceApproval = properties.requireSourceApproval,
                    requirePublicationApproval = properties.requirePublicationApproval,
                    rolesDecide = rolesDecide,
                ),
            )
    }
}
