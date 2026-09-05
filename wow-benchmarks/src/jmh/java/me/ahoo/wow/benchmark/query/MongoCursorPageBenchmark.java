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

import me.ahoo.wow.api.query.CursorPage;
import me.ahoo.wow.api.query.CursorQuery;
import me.ahoo.wow.api.query.MatchAllFilter;
import me.ahoo.wow.api.query.Projection;
import me.ahoo.wow.api.query.QueryField;
import me.ahoo.wow.api.query.Sort;
import me.ahoo.wow.mongo.query.MongoCursorCodec;
import me.ahoo.wow.mongo.query.MongoCursorDocumentsKt;
import me.ahoo.wow.mongo.query.MongoCursorProjection;
import org.bson.Document;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 200, timeUnit = TimeUnit.MILLISECONDS)
@Fork(3)
@Threads(1)
public class MongoCursorPageBenchmark {
    @Param({"10", "1000"}) public int pageSize;
    @Param({"all_fields", "hidden_nested"}) public String shape;
    private final List<String> sortFields = List.of(
            "state.a.rank", "state.a.weight", "state.b.rank", "state.b.weight");
    private CursorQuery query;
    private MongoCursorProjection projection;

    @Setup
    public void setup() {
        Projection requestedProjection = switch (shape) {
            case "all_fields" -> Projection.Companion.getALL();
            case "hidden_nested" -> new Projection(List.of(new QueryField("name")), List.of());
            default -> throw new IllegalArgumentException(shape);
        };
        query = new CursorQuery(MatchAllFilter.INSTANCE, requestedProjection,
                sortFields.stream().map(path -> new Sort(new QueryField(path), Sort.Direction.ASC)).toList(),
                pageSize, null);
        projection = MongoCursorDocumentsKt.withCursorFields(requestedProjection, sortFields);

        CursorPage<Document> page = toCursorPage();
        if (page.getList().size() != pageSize
                || !("name-" + pageSize).equals(page.getList().get(pageSize - 1).getString("name"))) {
            throw new IllegalStateException("wrong returned rows");
        }
        List<?> values = MongoCursorCodec.INSTANCE.decode(page.getNextCursor(), 4);
        if (!values.equals(List.of(pageSize, pageSize + 1, pageSize + 2, pageSize + 3))) {
            throw new IllegalStateException("cursor must use the last returned row");
        }
        Document expected = new Document("name", "name-1");
        if (shape.equals("all_fields")) {
            expected.append("state", new Document("a", new Document("rank", 1).append("weight", 2))
                    .append("b", new Document("rank", 3).append("weight", 4)));
        }
        if (!expected.equals(page.getList().get(0))) {
            throw new IllegalStateException("wrong payload or hidden field cleanup");
        }
    }

    @Benchmark
    public CursorPage<Document> toCursorPage() {
        List<Document> documents = new ArrayList<>(pageSize + 1);
        for (int row = 1; row <= pageSize + 1; row++) {
            documents.add(new Document("name", "name-" + row).append("state",
                    new Document("a", new Document("rank", row).append("weight", row + 1))
                            .append("b", new Document("rank", row + 2).append("weight", row + 3))));
        }
        return MongoCursorDocumentsKt.toCursorPage(documents, query, projection, sortFields, Set.of(), document -> document);
    }
}
