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
import me.ahoo.wow.api.query.analytics.AnalyticsBucketWindow;
import me.ahoo.wow.api.query.analytics.AnalyticsCompleteness;
import me.ahoo.wow.api.query.analytics.AnalyticsConsistency;
import me.ahoo.wow.api.query.analytics.AnalyticsGrouping;
import me.ahoo.wow.api.query.analytics.AnalyticsMetric;
import me.ahoo.wow.api.query.analytics.AnalyticsMetricKind;
import me.ahoo.wow.api.query.analytics.AnalyticsPage;
import me.ahoo.wow.api.query.analytics.AnalyticsQuery;
import me.ahoo.wow.modeling.MaterializedNamedAggregate;
import me.ahoo.wow.query.analytics.AnalyticsQueryService;
import me.ahoo.wow.query.backend.BackendAnalyticsCompleteness;
import me.ahoo.wow.query.backend.BackendAnalyticsConsistency;
import me.ahoo.wow.query.backend.BackendAnalyticsPage;
import me.ahoo.wow.query.cursor.QueryCursorLeaseCreateResult;
import me.ahoo.wow.query.cursor.QueryCursorLeaseEntry;
import me.ahoo.wow.query.cursor.QueryCursorLeaseId;
import me.ahoo.wow.query.cursor.QueryCursorLeaseStore;
import me.ahoo.wow.query.cursor.StoredQueryCursorLease;
import me.ahoo.wow.query.gateway.AnalyticsQueryGateway;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PublicAnalyticsJavaCompatibilityTest {

    @Test
    void shouldCompileAndCallThePublicJavaContracts() {
        AnalyticsQuery query = new AnalyticsQuery(
            new Condition(),
            AnalyticsGrouping.global(),
            List.of(new AnalyticsMetric("count", AnalyticsMetricKind.DOCUMENT_COUNT, null)),
            new AnalyticsBucketWindow(1, null),
            null,
            AnalyticsConsistency.EVENTUAL,
            AnalyticsCompleteness.EXACT
        );
        AnalyticsPage expected = new AnalyticsPage(
            List.of(),
            null,
            AnalyticsConsistency.EVENTUAL,
            AnalyticsCompleteness.EXACT
        );
        AnalyticsQueryService service = new JavaAnalyticsQueryService(expected);
        AnalyticsQueryGateway gateway = (call, request) -> Mono.just(expected);
        QueryCursorLeaseStore store = new JavaCursorLeaseStore();
        BackendAnalyticsPage backendPage = new BackendAnalyticsPage(
            List.of(),
            null,
            BackendAnalyticsConsistency.EVENTUAL,
            BackendAnalyticsCompleteness.EXACT
        );

        assertEquals(expected, service.analyze(query).block());
        assertEquals(expected, gateway.analyze(null, query).block());
        assertEquals(0L, store.scanExpired(Instant.EPOCH, null, 10).count().block());
        assertEquals(BackendAnalyticsConsistency.EVENTUAL, backendPage.getConsistency());
    }

    private static final class JavaAnalyticsQueryService implements AnalyticsQueryService {
        private final NamedAggregate namedAggregate = new MaterializedNamedAggregate("sales", "order");
        private final AnalyticsPage result;

        private JavaAnalyticsQueryService(AnalyticsPage result) {
            this.result = result;
        }

        @Override
        public NamedAggregate getNamedAggregate() {
            return namedAggregate;
        }

        @Override
        public Mono<AnalyticsPage> analyze(AnalyticsQuery query) {
            return Mono.just(result);
        }
    }

    private static final class JavaCursorLeaseStore implements QueryCursorLeaseStore {
        @Override
        public Mono<QueryCursorLeaseCreateResult> create(QueryCursorLeaseEntry entry) {
            return Mono.just(QueryCursorLeaseCreateResult.CREATED);
        }

        @Override
        public Mono<StoredQueryCursorLease> load(QueryCursorLeaseId id) {
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> compareAndDelete(StoredQueryCursorLease expected) {
            return Mono.just(false);
        }

        @Override
        public Flux<StoredQueryCursorLease> scanExpired(Instant before, QueryCursorLeaseId afterId, int limit) {
            return Flux.empty();
        }
    }
}
