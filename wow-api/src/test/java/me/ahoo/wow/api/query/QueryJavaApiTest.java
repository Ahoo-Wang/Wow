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

package me.ahoo.wow.api.query;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryJavaApiTest {
    @Test
    void shouldUseTheSharedQueryModelFromJava() {
        var status = new LogicalField("state.status");
        var query = new Query(
            QueryExpressions.eq("state.status", JsonNodeFactory.instance.textNode("ACTIVE")),
            new QueryProjection.Include(Set.of(status)),
            List.of(new QuerySort(status, QuerySortDirection.ASC)),
            new QueryScope(),
            new QueryBudget()
        );

        assertEquals(Set.of(status), ((QueryProjection.Include) query.getProjection()).getFields());
    }
}
