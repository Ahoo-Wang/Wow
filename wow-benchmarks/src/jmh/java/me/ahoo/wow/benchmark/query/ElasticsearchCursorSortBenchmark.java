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

package me.ahoo.wow.benchmark.query;

import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.CursorPage;
import me.ahoo.wow.api.query.CursorQuery;
import me.ahoo.wow.api.query.ICursorQuery;
import me.ahoo.wow.api.query.MatchAllFilter;
import me.ahoo.wow.api.query.Projection;
import me.ahoo.wow.api.query.QueryField;
import me.ahoo.wow.api.query.Sort;
import me.ahoo.wow.api.query.schema.QueryCapability;
import me.ahoo.wow.api.query.schema.QueryCardinality;
import me.ahoo.wow.api.query.schema.QueryModel;
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchFilterCompiler;
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryBackend;
import me.ahoo.wow.elasticsearch.query.ElasticsearchSortCompiler;
import me.ahoo.wow.elasticsearch.query.event.EventStreamFilterCompiler;
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler;
import me.ahoo.wow.modeling.MaterializedNamedAggregate;
import me.ahoo.wow.query.ResolvedQuery;
import me.ahoo.wow.query.schema.QueryFieldBinding;
import me.ahoo.wow.query.schema.QueryFieldSchema;
import me.ahoo.wow.query.schema.QueryModelSchema;
import me.ahoo.wow.query.schema.QueryRewriteMode;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgsAppend = {"-Xms256m", "-Xmx256m"})
@Threads(1)
public class ElasticsearchCursorSortBenchmark {
    @Param({"flat", "nested"}) public String shape;
    @Param({"2", "16"}) public int width;
    private QueryModelSchema schema;
    private List<Sort> sorts;
    private ResolvedQuery<ICursorQuery> query;
    private CursorBackend backend;

    @Setup
    public void setup() {
        boolean nested = shape.equals("nested");
        Map<QueryField, QueryFieldSchema> fields = new LinkedHashMap<>();
        List<Sort> inputSorts = new ArrayList<>();
        for (int index = 0; index < width; index++) {
            QueryField logical = new QueryField("logical.field" + index);
            QueryField resolved = new QueryField("document.field" + index);
            QueryField physical = new QueryField((nested ? "body" : "state") + ".field" + index);
            fields.put(logical, new QueryFieldSchema(
                    null, null, null, Set.of(), true, false, QueryCardinality.SINGLE,
                    null, false, Map.of(QueryCapability.Companion.getSORT(),
                    new QueryFieldBinding(resolved, physical, null)),
                    null, QueryRewriteMode.REQUIRED, null, null));
            inputSorts.add(new Sort(logical, index % 2 == 0 ? Sort.Direction.ASC : Sort.Direction.DESC));
        }
        schema = new QueryModelSchema(nested ? QueryModel.Companion.getEVENT_STREAM()
                : QueryModel.Companion.getSNAPSHOT(), Set.of(), Map.copyOf(fields));
        sorts = List.copyOf(inputSorts);
        query = new ResolvedQuery<>(new CursorQuery(
                MatchAllFilter.INSTANCE, Projection.Companion.getALL(), sorts, 10, null), schema);
        backend = new CursorBackend(nested ? EventStreamFilterCompiler.INSTANCE : SnapshotFilterCompiler.INSTANCE);

        List<SortOptions> ordinary = ordinarySort();
        if (ordinary.size() != width) throw new IllegalStateException("wrong sort count");
        for (int index = 0; index < width; index++) {
            FieldSort field = ordinary.get(index).field();
            String expected = (nested ? "body" : "state") + ".field" + index;
            SortOrder order = index % 2 == 0 ? SortOrder.Asc : SortOrder.Desc;
            if (!field.field().equals(expected) || field.order() != order || field.missing() != null
                    || (nested ? field.nested() == null || !field.nested().path().equals("body")
                    : field.nested() != null)) {
                throw new IllegalStateException("wrong ordinary sort at " + index);
            }
        }
        cursorRequest();
    }

    @Benchmark
    public List<SortOptions> ordinarySort() {
        return ElasticsearchSortCompiler.INSTANCE.compile(sorts, schema);
    }

    // Includes filter compilation, request and Mono assembly; never subscribes or performs I/O.
    @Benchmark
    public Mono<CursorPage<ObjectNode>> cursorRequest() {
        return backend.cursor(query);
    }

    private static final class CursorBackend extends AbstractElasticsearchQueryBackend {
        private final NamedAggregate namedAggregate = new MaterializedNamedAggregate("benchmark", "cursor");
        private final AbstractElasticsearchFilterCompiler filterCompiler;

        private CursorBackend(AbstractElasticsearchFilterCompiler filterCompiler) {
            this.filterCompiler = filterCompiler;
        }

        @Override
        public NamedAggregate getNamedAggregate() {
            return namedAggregate;
        }

        @Override
        public AbstractElasticsearchFilterCompiler getFilterCompiler() {
            return filterCompiler;
        }

        @Override
        public String getIndexName() {
            return "wow.benchmark.cursor";
        }

        @Override
        public ReactiveElasticsearchClient getElasticsearchClient() {
            throw new AssertionError("cursor assembly must not access the client");
        }
    }
}
