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
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class RouteTemplateTest {
    @ParameterizedTest
    @CsvSource(
        "/cart/snapshot/count, /cart/{id}/count, true",
        "/cart/{id}/state/tracing, /cart/{id}/state/{version}, true",
        "/cart/{id}/count, /cart/{cartId}/count, true",
        "/cart/{id}/count, /cart/snapshot/count, false",
        "/cart/snapshot/{afterId}/{limit}, /cart/{id}/{version}/compensate, false",
        "/cart/{id}/{version}/compensate, /cart/snapshot/{afterId}/{limit}, false",
        "/cart/snapshot/count, /cart/event/count, false",
        "/cart/{id}/count, /cart/{id}/count/state, false",
        "/files/a/b/c, /files/{*path}, true",
        "/files, /files/{*path}, true",
        "/files/{*path}, /files/{name}, false",
        "/files/{*path}, /files/**, true",
        "/cart/1/count, /cart/{id:\\d+}/count, false",
        "/cart/{id:\\d+}/count, /cart/{id}/count, true",
        "/cart/{id:\\d+}/count, /cart/{name:\\d+}/count, true",
        "/cart/{id:\\d+}/count, /cart/{name:[a-z]+}/count, false",
        "/cart/{id}/count, /cart/{id:\\d+}/count, false",
    )
    fun `should decide whether a template's paths are within another's`(
        inner: String,
        outer: String,
        expected: Boolean
    ) {
        RouteTemplate(inner).isWithin(RouteTemplate(outer)).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "/cart/snapshot/count, /cart/{id}/count, true",
        "/cart/{id}/count, /cart/{cartId}/count, false",
        "/cart/{id}/count, /cart/snapshot/count, false",
        "/cart/snapshot/{afterId}/{limit}, /cart/{id}/{version}/compensate, false",
    )
    fun `should decide whether a template's paths are a proper subset of another's`(
        inner: String,
        outer: String,
        expected: Boolean
    ) {
        RouteTemplate(inner).isStrictlyWithin(RouteTemplate(outer)).assert().isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource(
        "/cart/{id}/count, /cart/{}/count",
        "/cart/*/count, /cart/{}/count",
        "/cart/{id}.json, /cart/{}.json",
        "/cart/{id:\\d+}, /cart/{:\\d+}",
        "/cart/{name:[a-z]+}, /cart/{:[a-z]+}",
        "/files/{*path}, /files/{*}",
        "/files/**, /files/{*}",
        "cart//{id}/, /cart/{}",
    )
    fun `should erase variable names from the shape`(path: String, shape: String) {
        RouteTemplate(path).shape.assert().isEqualTo(shape)
    }

    @Test
    fun `should not give templates with different constraints the same shape`() {
        val digits = RouteTemplate("/cart/{id:\\d+}/count")
        val letters = RouteTemplate("/cart/{name:[a-z]+}/count")

        digits.shape.assert().isNotEqualTo(letters.shape)
        digits.isWithin(letters).assert().isFalse()
        letters.isWithin(digits).assert().isFalse()
    }
}
