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

package me.ahoo.wow.openapi.catalog

import me.ahoo.test.asserts.assert
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RouteTemplateTest {
    @ParameterizedTest
    @CsvSource(
        "/cart/snapshot/count, /cart/{id}/count, true",
        "/cart/snapshot/{afterId}/{limit}, /cart/{id}/{version}/compensate, true",
        "/cart/{id}/state/tracing, /cart/{id}/state/{version}, true",
        "/cart/{id}/count, /cart/{cartId}/count, true",
        "/cart/snapshot/count, /cart/event/count, false",
        "/cart/{id}/pay, /cart/snapshot/single, false",
        "/cart/{id}/count, /cart/{id}/count/state, false",
        "/tenant/{tenantId}/cart/{id}/pay, /owner/{ownerId}/cart/snapshot/pay, false",
        "/cart/{id}.json, /cart/snapshot, true",
        "/files/{*path}, /files/a/b/c, true",
        "/files/{*path}, /files, true",
        "/files/**, /other/a, false",
    )
    fun `should decide whether two templates match a common path`(left: String, right: String, expected: Boolean) {
        RouteTemplate(left).overlaps(RouteTemplate(right)).assert().isEqualTo(expected)
        RouteTemplate(right).overlaps(RouteTemplate(left)).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "/cart/snapshot/count, /cart/{id}/count",
        "/cart/snapshot/{afterId}/{limit}, /cart/{id}/{version}/compensate",
        "/cart/{id}/state/tracing, /cart/{id}/state/{version}",
        "/files/a/b, /files/{*path}",
        "/files/{name}, /files/{*path}",
        "/cart, /cart/{id}",
    )
    fun `should order the more specific template first`(first: String, second: String) {
        (RouteTemplate(first) < RouteTemplate(second)).assert().isTrue()
        (RouteTemplate(second) > RouteTemplate(first)).assert().isTrue()
    }

    @ParameterizedTest
    @CsvSource(
        "/cart/{id}/count, /cart/{}/count",
        "/cart/{id}.json, /cart/{}.json",
        "/files/{*path}, /files/{*}",
        "/files/**, /files/{*}",
        "cart//{id}/, /cart/{}",
    )
    fun `should erase variable names from the shape`(path: String, shape: String) {
        RouteTemplate(path).shape.assert().isEqualTo(shape)
    }
}
