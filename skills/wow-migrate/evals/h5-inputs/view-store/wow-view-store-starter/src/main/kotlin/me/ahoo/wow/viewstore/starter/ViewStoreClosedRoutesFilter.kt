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

package me.ahoo.wow.viewstore.starter

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import me.ahoo.wow.openapi.contract.HttpRouteContract
import me.ahoo.wow.spring.boot.starter.openapi.OpenApiDocumentFilter

/**
 * Takes the routes [ViewStoreRouteGuard] closes out of the document once Wow has merged them, so it shows only what
 * is served.
 */
internal class ViewStoreClosedRoutesFilter(
    private val closedContracts: List<HttpRouteContract>,
) : OpenApiDocumentFilter {
    override fun filter(openApi: OpenAPI) {
        val documented = openApi.paths ?: return
        closedContracts.forEach { contract ->
            val item = documented[contract.path] ?: return@forEach
            item.operation(PathItem.HttpMethod.valueOf(contract.method), null)
            if (item.readOperations().isEmpty()) {
                documented.remove(contract.path)
            }
        }
    }
}
