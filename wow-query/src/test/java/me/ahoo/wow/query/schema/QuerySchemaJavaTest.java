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
import me.ahoo.wow.api.query.schema.QueryCapability;
import me.ahoo.wow.api.query.schema.QueryValueType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class QuerySchemaJavaTest {
    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeV9QueryFieldDeclarationConstructor() {
        QueryFieldDeclaration declaration = new QueryFieldDeclaration(
            DeclarationValue.Unset.INSTANCE,
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

        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getTitle());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getDescription());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getEnumValues());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getValueTypes());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getNullable());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getRequired());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getCardinality());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getSemanticType());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getDynamicChildren());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getMaskRule());
        assertEquals(Set.of(10, 0), constructorArities(QueryFieldDeclaration.class));
    }

    @Test
    void shouldExposeV9QueryFieldSchemaConstructor() {
        Set<QueryValueType> valueTypes = Set.of(new QueryValueType("STRING"));
        Map<QueryCapability, QueryFieldBinding> bindings = Map.of();
        QueryFieldSchema schema = new QueryFieldSchema(
            "Name",
            "Description",
            null,
            valueTypes,
            true,
            true,
            QueryCardinality.SINGLE,
            null,
            false,
            bindings,
            null,
            QueryRewriteMode.NONE,
            null,
            null
        );

        assertEquals("Name", schema.getTitle());
        assertEquals("Description", schema.getDescription());
        assertNull(schema.getEnumValues());
        assertEquals(valueTypes, schema.getValueTypes());
        assertEquals(true, schema.getNullable());
        assertEquals(true, schema.getRequired());
        assertEquals(QueryCardinality.SINGLE, schema.getCardinality());
        assertNull(schema.getSemanticType());
        assertEquals(false, schema.getDynamicChildren());
        assertEquals(bindings, schema.getBindings());
        assertNull(schema.getProjectionField());
        assertEquals(QueryRewriteMode.NONE, schema.getRewriteMode());
        assertNull(schema.getResponseField());
        assertEquals(false, schema.getMasked());
        assertEquals(Set.of(14), constructorArities(QueryFieldSchema.class));
    }

    @Test
    void shouldExposeV9LogicalQueryFieldSchemaConstructor() {
        Set<QueryValueType> valueTypes = Set.of(new QueryValueType("STRING"));
        LogicalQueryFieldSchema schema = new LogicalQueryFieldSchema(
            "Name",
            "Description",
            null,
            valueTypes,
            true,
            true,
            QueryCardinality.SINGLE,
            null,
            false,
            null
        );

        assertEquals("Name", schema.getTitle());
        assertEquals("Description", schema.getDescription());
        assertNull(schema.getEnumValues());
        assertEquals(valueTypes, schema.getValueTypes());
        assertEquals(true, schema.getNullable());
        assertEquals(true, schema.getRequired());
        assertEquals(QueryCardinality.SINGLE, schema.getCardinality());
        assertNull(schema.getSemanticType());
        assertEquals(false, schema.getDynamicChildren());
        assertNull(schema.getMaskRule());
        assertEquals(Set.of(10), constructorArities(LogicalQueryFieldSchema.class));
    }

    private static Set<Integer> constructorArities(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
            .filter(constructor -> !constructor.isSynthetic())
            .map(Constructor::getParameterCount)
            .collect(Collectors.toSet());
    }
}
