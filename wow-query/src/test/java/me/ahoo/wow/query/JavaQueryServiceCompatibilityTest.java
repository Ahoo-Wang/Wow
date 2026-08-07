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

package me.ahoo.wow.query;

import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.Condition;
import me.ahoo.wow.api.query.DynamicDocument;
import me.ahoo.wow.api.query.IListQuery;
import me.ahoo.wow.api.query.IPagedQuery;
import me.ahoo.wow.api.query.ISingleQuery;
import me.ahoo.wow.api.query.PagedList;
import me.ahoo.wow.modeling.MaterializedNamedAggregate;
import me.ahoo.wow.query.filter.QueryType;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaQueryServiceCompatibilityTest {

    @Test
    void shouldCompileAndCallThePublicJavaContract() {
        QueryService<String> service = new JavaQueryService();

        assertFalse(QueryType.COUNT.isDynamic());
        assertFalse(service.getAggregateName().isBlank());
    }

    private static final class JavaQueryService implements QueryService<String> {
        private final NamedAggregate namedAggregate = new MaterializedNamedAggregate("sales", "order");

        @Override
        public NamedAggregate getNamedAggregate() {
            return namedAggregate;
        }

        @Override
        public Mono<String> single(ISingleQuery singleQuery) {
            return Mono.empty();
        }

        @Override
        public Mono<DynamicDocument> dynamicSingle(ISingleQuery singleQuery) {
            return Mono.empty();
        }

        @Override
        public Flux<String> list(IListQuery listQuery) {
            return Flux.empty();
        }

        @Override
        public Flux<DynamicDocument> dynamicList(IListQuery listQuery) {
            return Flux.empty();
        }

        @Override
        public Mono<PagedList<String>> paged(IPagedQuery pagedQuery) {
            return Mono.empty();
        }

        @Override
        public Mono<PagedList<DynamicDocument>> dynamicPaged(IPagedQuery pagedQuery) {
            return Mono.empty();
        }

        @Override
        public Mono<Long> count(Condition condition) {
            return Mono.empty();
        }
    }
}
