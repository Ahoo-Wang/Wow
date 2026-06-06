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

package me.ahoo.wow.openapi.aggregate

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

internal class TenantOwnerRouteSummarySpecTest {

    @Test
    fun `should build summary with operation only`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .build()
        summary.assert().isEqualTo("Get Aggregate")
    }

    @Test
    fun `should build summary with tenant appended`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .appendTenant(true)
            .build()
        summary.assert().isEqualTo("Get Aggregate Within Tenant")
    }

    @Test
    fun `should build summary with owner appended`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .appendOwner(true)
            .build()
        summary.assert().isEqualTo("Get Aggregate Within Owner")
    }

    @Test
    fun `should build summary with tenant and owner appended`() {
        val summary = TenantOwnerRouteSummarySpec()
            .operationSummary("Get Aggregate")
            .appendTenant(true)
            .appendOwner(true)
            .build()
        summary.assert().isEqualTo("Get Aggregate Within Tenant Owner")
    }
}
