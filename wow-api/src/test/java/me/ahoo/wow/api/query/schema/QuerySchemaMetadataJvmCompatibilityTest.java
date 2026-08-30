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

package me.ahoo.wow.api.query.schema;

import me.ahoo.wow.api.query.LogicalField;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QuerySchemaMetadataJvmCompatibilityTest {
    @Test
    void shouldKeepQueryFieldSchemaMetadataConstructorFromOriginMain() {
        QueryFieldSchemaMetadata metadata = new QueryFieldSchemaMetadata(
            new LogicalField("state.name"),
            "Name",
            "Description",
            null,
            Set.of(new QueryValueType("STRING")),
            true,
            true,
            QueryCardinality.SINGLE,
            null,
            false,
            Set.of(new QueryCapability("PRESENCE"))
        );

        assertFalse(metadata.getMasked());
        assertEquals(Set.of(12, 11), constructorArities(QueryFieldSchemaMetadata.class));
    }

    private static Set<Integer> constructorArities(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
            .filter(constructor -> !constructor.isSynthetic())
            .map(Constructor::getParameterCount)
            .collect(Collectors.toSet());
    }
}
