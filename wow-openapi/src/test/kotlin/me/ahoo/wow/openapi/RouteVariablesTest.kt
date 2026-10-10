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

import me.ahoo.test.asserts.assert
import me.ahoo.wow.rest.RouteVariables
import me.ahoo.wow.serialization.MessageRecords
import org.junit.jupiter.api.Test

/**
 * wow-rest-contract defines the path variable names itself, so it does not depend on wow-core; the route contributors
 * and handlers still name some of them through [MessageRecords]. Both must stay the same strings.
 */
internal class RouteVariablesTest {

    @Test
    fun `route variables match the message record field names`() {
        RouteVariables.ID.assert().isEqualTo(MessageRecords.ID)
        RouteVariables.TENANT_ID.assert().isEqualTo(MessageRecords.TENANT_ID)
        RouteVariables.OWNER_ID.assert().isEqualTo(MessageRecords.OWNER_ID)
        RouteVariables.VERSION.assert().isEqualTo(MessageRecords.VERSION)
        RouteVariables.CREATE_TIME.assert().isEqualTo(MessageRecords.CREATE_TIME)
    }
}
