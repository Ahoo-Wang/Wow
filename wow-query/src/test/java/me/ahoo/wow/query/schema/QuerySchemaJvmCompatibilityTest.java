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

package me.ahoo.wow.query.schema;

import me.ahoo.wow.api.query.schema.QueryCardinality;
import me.ahoo.wow.api.query.schema.QueryValueType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class QuerySchemaJvmCompatibilityTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldKeepQueryFieldDeclarationConstructorFromOriginMain() {
        QueryFieldDeclaration declaration = new QueryFieldDeclaration(
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE,
            DeclarationValue.Unset.INSTANCE
        );

        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getMaskRule());
    }

    @Test
    void shouldKeepQueryFieldSchemaConstructorFromOriginMain() {
        QueryFieldSchema schema = new QueryFieldSchema(
            "Name",
            "Description",
            null,
            Set.of(new QueryValueType("STRING")),
            true,
            true,
            QueryCardinality.SINGLE,
            null,
            false,
            Map.of(),
            null
        );

        assertNull(schema.getMaskRule());
    }

    @Test
    void shouldKeepLogicalQueryFieldSchemaConstructorFromOriginMain() {
        LogicalQueryFieldSchema schema = new LogicalQueryFieldSchema(
            "Name",
            "Description",
            null,
            Set.of(new QueryValueType("STRING")),
            true,
            true,
            QueryCardinality.SINGLE,
            null,
            false
        );

        assertNull(schema.getMaskRule());
    }
}
