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

import me.ahoo.wow.api.query.AggregationDateUnit;
import me.ahoo.wow.api.query.AggregationExpression;
import me.ahoo.wow.api.query.AggregationFunction;
import me.ahoo.wow.api.query.AggregationGroup;
import me.ahoo.wow.api.query.AggregationMetric;
import me.ahoo.wow.api.query.AggregationQuery;
import me.ahoo.wow.api.query.MatchAllFilter;
import me.ahoo.wow.api.query.QueryField;
import me.ahoo.wow.api.query.schema.QueryCapability;
import me.ahoo.wow.api.query.schema.QueryCardinality;
import me.ahoo.wow.api.query.schema.QueryModel;
import me.ahoo.wow.api.query.schema.QuerySemanticType;
import me.ahoo.wow.api.query.schema.QueryValueType;
import me.ahoo.wow.api.query.schema.Temporal;
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationCompiler;
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationMetric;
import me.ahoo.wow.elasticsearch.query.aggregation.ElasticsearchAggregationPlan;
import me.ahoo.wow.mongo.query.aggregation.MongoAggregationCompiler;
import me.ahoo.wow.query.schema.QueryFieldBinding;
import me.ahoo.wow.query.schema.QueryFieldSchema;
import me.ahoo.wow.query.schema.QueryModelSchema;
import me.ahoo.wow.query.schema.QueryRewriteMode;
import org.bson.BsonDocument;
import org.bson.conversions.Bson;
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
@Fork(3)
@Threads(1)
public class AggregationCompilerBenchmark {
    @Param({"mongo", "elasticsearch"}) public String backend;
    @Param({"known_terms", "unknown_terms", "known_histogram", "known_epoch",
            "unknown_date", "known_metric", "count_only"}) public String shape;
    @Param({"1", "16"}) public int width;
    private QueryModelSchema schema;
    private AggregationQuery query;
    private final MongoAggregationCompiler mongo = new MongoAggregationCompiler(
            me.ahoo.wow.mongo.query.snapshot.SnapshotFilterCompiler.INSTANCE);
    private final ElasticsearchAggregationCompiler elasticsearch = new ElasticsearchAggregationCompiler(
            me.ahoo.wow.elasticsearch.query.snapshot.SnapshotFilterCompiler.INSTANCE);

    @Setup
    public void setup() {
        Map<QueryField, QueryFieldSchema> fields = new LinkedHashMap<>();
        List<AggregationGroup> groups = new ArrayList<>();
        List<AggregationMetric> metrics = new ArrayList<>();
        for (int index = 0; index < width; index++) {
            QueryField logical = new QueryField("state.field" + index);
            QueryField resolved = new QueryField("document.field" + index);
            QueryField physical = new QueryField("storage.field" + index);
            QueryCapability capability = switch (shape) {
                case "known_histogram", "known_metric" -> QueryCapability.Companion.getAGGREGATE_NUMERIC();
                case "known_epoch", "unknown_date" -> QueryCapability.Companion.getAGGREGATE_TEMPORAL();
                default -> QueryCapability.Companion.getAGGREGATE_TERMS();
            };
            if (shape.startsWith("known_")) {
                QuerySemanticType semantic = shape.equals("known_epoch")
                        ? new Temporal.Epoch(TimeUnit.MICROSECONDS) : null;
                QueryValueType valueType = shape.equals("known_terms")
                        ? QueryValueType.Companion.getSTRING() : QueryValueType.Companion.getINTEGER();
                fields.put(logical, new QueryFieldSchema(
                        null, null, null, Set.of(valueType), false, true, QueryCardinality.SINGLE,
                        semantic, false, Map.of(capability, new QueryFieldBinding(resolved, physical, null)),
                        null, QueryRewriteMode.REQUIRED, null, null));
            }
            QueryField input = shape.equals("known_epoch") ? resolved : logical;
            String alias = "group" + index;
            switch (shape) {
                case "known_terms", "unknown_terms" -> groups.add(new AggregationGroup.Terms(input, alias));
                case "known_histogram" -> groups.add(new AggregationGroup.Histogram(input, alias, 10.0));
                case "known_epoch", "unknown_date" -> groups.add(new AggregationGroup.DateHistogram(
                        input, alias, AggregationDateUnit.DAY, "UTC"));
                case "known_metric" -> metrics.add(new AggregationMetric.Numeric(
                        AggregationFunction.SUM, new AggregationExpression.Field(input), "metric" + index));
                case "count_only" -> metrics.add(new AggregationMetric.Count("count" + index));
                default -> throw new IllegalArgumentException(shape);
            }
        }
        if (!groups.isEmpty()) metrics.add(new AggregationMetric.Count("count"));
        schema = new QueryModelSchema(QueryModel.Companion.getSNAPSHOT(), Set.of(), fields);
        query = new AggregationQuery(MatchAllFilter.INSTANCE, List.of(), groups, metrics, List.of(), 100);
        verifyPlan(compile());
    }

    @Benchmark
    public Object compile() {
        return backend.equals("mongo") ? mongo.compile(query, schema) : elasticsearch.compile(query, schema);
    }

    private void verifyPlan(Object result) {
        String expected = (shape.startsWith("known_") ? "storage" : "state") + ".field0";
        if (backend.equals("mongo")) {
            List<?> pipeline = (List<?>) result;
            List<BsonDocument> documents = pipeline.stream().map(stage -> ((Bson) stage).toBsonDocument()).toList();
            String json = documents.toString();
            BsonDocument group = documents.stream().filter(stage -> stage.containsKey("$group"))
                    .findFirst().orElseThrow().getDocument("$group");
            if (query.getGroupBy().isEmpty() && !group.get("_id").isNull()) {
                throw new IllegalStateException("summary must use a null group key");
            }
            if (!shape.equals("count_only") && !json.contains(expected)) {
                throw new IllegalStateException("missing path: " + expected);
            }
            return;
        }
        ElasticsearchAggregationPlan plan = (ElasticsearchAggregationPlan) result;
        if (plan.getGroupSources().size() != query.getGroupBy().size()
                || plan.getMetrics().size() != query.getMetrics().size()) {
            throw new IllegalStateException("wrong compiled dimensions");
        }
        String actual = switch (shape) {
            case "known_terms", "unknown_terms" -> plan.getGroupSources().get(0).value().terms().field();
            case "known_histogram" -> plan.getGroupSources().get(0).value().histogram().field();
            case "unknown_date" -> plan.getGroupSources().get(0).value().dateHistogram().field();
            case "known_epoch" -> plan.getRuntimeMappings().get("__wow_date_histogram_0")
                    .script().params().get("field").to(String.class);
            case "known_metric" -> ((ElasticsearchAggregationMetric.Numeric) plan.getMetrics().get(0)).getField();
            case "count_only" -> expected;
            default -> throw new IllegalArgumentException(shape);
        };
        if (!expected.equals(actual)) throw new IllegalStateException("wrong path: " + actual);
        int expectedRuntimeFields = shape.equals("known_epoch") ? width : 0;
        if (plan.getRuntimeMappings().size() != expectedRuntimeFields) {
            throw new IllegalStateException("wrong runtime field count");
        }
    }
}
