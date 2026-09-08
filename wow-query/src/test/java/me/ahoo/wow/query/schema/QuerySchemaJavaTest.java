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

import me.ahoo.wow.api.query.schema.QueryValueKind;
import me.ahoo.wow.api.query.schema.QueryValueType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class QuerySchemaJavaTest {
    @Test
    void shouldExposeRecursiveDeclarationBuilderToJava() {
        QueryFieldDeclarationBuilder item = new QueryFieldDeclarationBuilder();
        item.valueTypes(QueryValueType.Companion.getSTRING());
        item.nullable(false);
        QueryFieldDeclarationBuilder array = new QueryFieldDeclarationBuilder();
        array.kind(QueryValueKind.ARRAY);
        array.items(item.build());
        QueryFieldDeclaration declaration = array.build();

        assertEquals(new DeclarationValue.Set<>(QueryValueKind.ARRAY), declaration.getKind());
        assertEquals(new DeclarationValue.Set<>(item.build()), declaration.getItems());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getAdditionalProperties());
        assertSame(DeclarationValue.Unset.INSTANCE, declaration.getAlternatives());
    }
}
