#!/usr/bin/env bash
# Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#      http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euo pipefail

[[ $# -eq 6 ]] || {
    echo "Usage: $0 WOW_QUERY_JAR WOW_MONGO_JAR WOW_ELASTICSEARCH_JAR WOW_STARTER_JAR RUNTIME_CLASSPATH KOTLIN_COMPILER_CLASSPATH" >&2
    exit 64
}

readonly WOW_QUERY_JAR="$1"
readonly WOW_MONGO_JAR="$2"
readonly WOW_ELASTICSEARCH_JAR="$3"
readonly WOW_STARTER_JAR="$4"
readonly RUNTIME_CLASSPATH="$5"
readonly KOTLIN_COMPILER_CLASSPATH="$6"
readonly FIXTURE_CLASSPATH="$WOW_QUERY_JAR:$WOW_MONGO_JAR:$WOW_ELASTICSEARCH_JAR:$WOW_STARTER_JAR:$RUNTIME_CLASSPATH"
readonly TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/query-api-source-check.XXXXXX")"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

[[ -f "$WOW_QUERY_JAR" ]] || fail "Published wow-query JAR is missing: $WOW_QUERY_JAR"
[[ -f "$WOW_MONGO_JAR" ]] || fail "Published wow-mongo JAR is missing: $WOW_MONGO_JAR"
[[ -f "$WOW_ELASTICSEARCH_JAR" ]] || fail "Published wow-elasticsearch JAR is missing: $WOW_ELASTICSEARCH_JAR"
[[ -f "$WOW_STARTER_JAR" ]] || fail "Published wow-spring-boot-starter JAR is missing: $WOW_STARTER_JAR"
[[ -n "$RUNTIME_CLASSPATH" ]] || fail "Runtime classpath is empty"
[[ -n "$KOTLIN_COMPILER_CLASSPATH" ]] || fail "Kotlin compiler classpath is empty"

IFS=':' read -r -a runtime_entries <<<"$RUNTIME_CLASSPATH"
for runtime_entry in "${runtime_entries[@]}"; do
    [[ -f "$runtime_entry" && "$runtime_entry" == *.jar ]] ||
        fail "Runtime classpath must contain dependency JARs only: $runtime_entry"
    [[ "$runtime_entry" != "$WOW_QUERY_JAR" ]] ||
        fail "Published wow-query JAR must not be duplicated on the runtime dependency classpath"
    [[ "$runtime_entry" != "$WOW_MONGO_JAR" ]] ||
        fail "Published wow-mongo JAR must not be duplicated on the runtime dependency classpath"
    [[ "$runtime_entry" != "$WOW_ELASTICSEARCH_JAR" ]] ||
        fail "Published wow-elasticsearch JAR must not be duplicated on the runtime dependency classpath"
    [[ "$runtime_entry" != "$WOW_STARTER_JAR" ]] ||
        fail "Published wow-spring-boot-starter JAR must not be duplicated on the runtime dependency classpath"
done

mkdir -p "$TEMP_DIR/java" "$TEMP_DIR/kotlin" "$TEMP_DIR/classes/java" "$TEMP_DIR/classes/kotlin"

cat >"$TEMP_DIR/java/StableMongoBackendApi.java" <<'EOF'
package external.fixture;

import java.util.Map;
import com.mongodb.client.model.Filters;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.expression.QueryValue;
import me.ahoo.wow.mongo.query.event.EventStreamConditionConverter;
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryService;
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceFactory;
import me.ahoo.wow.mongo.query.backend.MongoNativeQueryTemplate;
import me.ahoo.wow.mongo.query.backend.MongoNativeQueryTemplateRegistry;
import me.ahoo.wow.mongo.query.backend.MongoQueryBackendFactory;
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryService;
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory;
import me.ahoo.wow.mongo.query.snapshot.SnapshotConditionConverter;
import me.ahoo.wow.query.QueryGateway;
import me.ahoo.wow.query.validation.QueryBudgetLimit;
import org.bson.Document;

public final class StableMongoBackendApi {
    public static MongoNativeQueryTemplateRegistry registry() {
        MongoNativeQueryTemplate template = parameters ->
            Filters.eq("tenantId", ((QueryValue.StringValue) parameters.get("tenant")).getValue());
        return new MongoNativeQueryTemplateRegistry(Map.of("tenant-eq", template));
    }

    public static MongoQueryBackendFactory factory(MongoDatabase database) {
        return new MongoQueryBackendFactory(database, registry(), QueryBudgetLimit.UNBOUNDED);
    }

    public static Object[] compatibilityServices(
        NamedAggregate aggregate,
        MongoDatabase database,
        MongoCollection<Document> collection,
        QueryGateway gateway
    ) {
        return new Object[]{
            new MongoSnapshotQueryService<Object>(aggregate, collection, SnapshotConditionConverter.INSTANCE),
            new MongoEventStreamQueryService(aggregate, collection, EventStreamConditionConverter.INSTANCE),
            new MongoSnapshotQueryServiceFactory(database),
            new MongoEventStreamQueryServiceFactory(database),
            new MongoSnapshotQueryService<Object>(aggregate, collection, gateway),
            new MongoEventStreamQueryService(aggregate, collection, gateway),
            new MongoSnapshotQueryServiceFactory(database, gateway),
            new MongoEventStreamQueryServiceFactory(database, gateway)
        };
    }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/StableMongoBackendApi.java"
echo "PASS: Java external stable Mongo backend API source"

cat >"$TEMP_DIR/kotlin/StableMongoBackendApi.kt" <<'EOF'
package external.fixture

import com.mongodb.client.model.Filters
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryService
import me.ahoo.wow.mongo.query.event.MongoEventStreamQueryServiceFactory
import me.ahoo.wow.mongo.query.backend.MongoNativeQueryTemplate
import me.ahoo.wow.mongo.query.backend.MongoNativeQueryTemplateRegistry
import me.ahoo.wow.mongo.query.backend.MongoQueryBackendFactory
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryService
import me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceFactory
import me.ahoo.wow.query.QueryGateway
import org.bson.Document

fun mongoFactory(database: MongoDatabase): MongoQueryBackendFactory {
    val template = MongoNativeQueryTemplate { parameters ->
        Filters.eq("tenantId", (parameters.getValue("tenant") as QueryValue.StringValue).value)
    }
    return MongoQueryBackendFactory(database, MongoNativeQueryTemplateRegistry(mapOf("tenant-eq" to template)))
}

@Suppress("DEPRECATION")
fun mongoCompatibilityServices(
    aggregate: NamedAggregate,
    database: MongoDatabase,
    collection: MongoCollection<Document>,
    gateway: QueryGateway
): List<Any> = listOf(
    MongoSnapshotQueryService<Any>(aggregate, collection),
    MongoEventStreamQueryService(aggregate, collection),
    MongoSnapshotQueryServiceFactory(database),
    MongoEventStreamQueryServiceFactory(database),
    MongoSnapshotQueryService<Any>(aggregate, collection, gateway),
    MongoEventStreamQueryService(aggregate, collection, gateway),
    MongoSnapshotQueryServiceFactory(database, gateway),
    MongoEventStreamQueryServiceFactory(database, gateway)
)
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name mongo-query-api-external-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin" "$TEMP_DIR/kotlin/StableMongoBackendApi.kt"
echo "PASS: Kotlin external stable Mongo backend API source"

cat >"$TEMP_DIR/java/StableElasticsearchBackendApi.java" <<'EOF'
package external.fixture;

import java.util.Map;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.expression.QueryValue;
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchNativeQueryTemplate;
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchNativeQueryTemplateRegistry;
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryBackendFactory;
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryService;
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory;
import me.ahoo.wow.elasticsearch.query.event.EventStreamConditionConverter;
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryService;
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory;
import me.ahoo.wow.elasticsearch.query.snapshot.SnapshotConditionConverter;
import me.ahoo.wow.query.QueryGateway;
import me.ahoo.wow.query.validation.QueryBudgetLimit;
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient;

public final class StableElasticsearchBackendApi {
    public static ElasticsearchNativeQueryTemplateRegistry registry() {
        ElasticsearchNativeQueryTemplate template = parameters -> Query.of(query -> query.term(term ->
            term.field("tenantId").value(((QueryValue.StringValue) parameters.get("tenant")).getValue())
        ));
        return new ElasticsearchNativeQueryTemplateRegistry(Map.of("tenant-eq", template));
    }

    public static ElasticsearchQueryBackendFactory factory(ReactiveElasticsearchClient client) {
        return new ElasticsearchQueryBackendFactory(client, registry(), QueryBudgetLimit.UNBOUNDED);
    }

    public static Object[] compatibilityServices(
        NamedAggregate aggregate,
        ReactiveElasticsearchClient client,
        QueryGateway gateway
    ) {
        return new Object[]{
            new ElasticsearchSnapshotQueryService<Object>(aggregate, client, SnapshotConditionConverter.INSTANCE),
            new ElasticsearchEventStreamQueryService(aggregate, client, EventStreamConditionConverter.INSTANCE),
            new ElasticsearchSnapshotQueryServiceFactory(client),
            new ElasticsearchEventStreamQueryServiceFactory(client),
            new ElasticsearchSnapshotQueryService<Object>(aggregate, client, gateway),
            new ElasticsearchEventStreamQueryService(aggregate, client, gateway),
            new ElasticsearchSnapshotQueryServiceFactory(client, gateway),
            new ElasticsearchEventStreamQueryServiceFactory(client, gateway)
        };
    }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/StableElasticsearchBackendApi.java"
echo "PASS: Java external stable Elasticsearch backend API source"

cat >"$TEMP_DIR/java/InternalElasticsearchEventDocumentFactory.java" <<'EOF'
package external.fixture;

import java.util.Map;
import me.ahoo.wow.event.DomainEventStream;
import static me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchEventStreamAppenderKt.toElasticsearchDocument;

public final class InternalElasticsearchEventDocumentFactory {
    public static Map<String, Object> encode(DomainEventStream eventStream) {
        return toElasticsearchDocument(eventStream);
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/InternalElasticsearchEventDocumentFactory.java" \
    >"$TEMP_DIR/internal-elasticsearch-event-document.out" 2>&1; then
    fail "Java external source unexpectedly compiled internal Elasticsearch event document factory"
fi
grep -Fq "toElasticsearchDocument" "$TEMP_DIR/internal-elasticsearch-event-document.out" ||
    fail "Java external source failed for an unexpected reason while checking internal Elasticsearch event document factory"
echo "PASS: Java external source cannot reference internal Elasticsearch event document factory"

cat >"$TEMP_DIR/kotlin/StableElasticsearchBackendApi.kt" <<'EOF'
package external.fixture

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchNativeQueryTemplate
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchNativeQueryTemplateRegistry
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryBackendFactory
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryService
import me.ahoo.wow.elasticsearch.query.event.ElasticsearchEventStreamQueryServiceFactory
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryService
import me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchSnapshotQueryServiceFactory
import me.ahoo.wow.query.QueryGateway
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

fun elasticsearchFactory(client: ReactiveElasticsearchClient): ElasticsearchQueryBackendFactory {
    val template = ElasticsearchNativeQueryTemplate { parameters ->
        Query.of { query ->
            query.term { term ->
                term.field("tenantId").value((parameters.getValue("tenant") as QueryValue.StringValue).value)
            }
        }
    }
    return ElasticsearchQueryBackendFactory(
        client,
        ElasticsearchNativeQueryTemplateRegistry(mapOf("tenant-eq" to template))
    )
}

@Suppress("DEPRECATION")
fun elasticsearchCompatibilityServices(
    aggregate: NamedAggregate,
    client: ReactiveElasticsearchClient,
    gateway: QueryGateway
): List<Any> = listOf(
    ElasticsearchSnapshotQueryService<Any>(aggregate, client),
    ElasticsearchEventStreamQueryService(aggregate, client),
    ElasticsearchSnapshotQueryServiceFactory(client),
    ElasticsearchEventStreamQueryServiceFactory(client),
    ElasticsearchSnapshotQueryService<Any>(aggregate, client, gateway),
    ElasticsearchEventStreamQueryService(aggregate, client, gateway),
    ElasticsearchSnapshotQueryServiceFactory(client, gateway),
    ElasticsearchEventStreamQueryServiceFactory(client, gateway)
)
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name elasticsearch-query-api-external-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin" "$TEMP_DIR/kotlin/StableElasticsearchBackendApi.kt"
echo "PASS: Kotlin external stable Elasticsearch backend API source"

cat >"$TEMP_DIR/java/MongoQueryServiceInjection.java" <<'EOF'
package external.fixture;

import com.mongodb.reactivestreams.client.MongoCollection;
import me.ahoo.wow.api.query.DynamicDocument;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.mongo.query.AbstractMongoQueryService;
import me.ahoo.wow.mongo.query.MongoProjectionConverter;
import me.ahoo.wow.mongo.query.MongoSortConverter;
import me.ahoo.wow.query.QueryService;
import me.ahoo.wow.query.converter.ConditionConverter;
import org.bson.Document;
import org.bson.conversions.Bson;

public final class MongoQueryServiceInjection {
    static final class MongoBypass extends AbstractMongoQueryService<Object> {
        MongoBypass(QueryService<Object> arbitrary) { super(arbitrary); }
        public NamedAggregate getNamedAggregate() { return null; }
        public MongoCollection<Document> getCollection() { return null; }
        public ConditionConverter<Bson> getConverter() { return null; }
        public MongoProjectionConverter getProjectionConverter() { return null; }
        public MongoSortConverter getSortConverter() { return null; }
        public Object toTypedResult(Document document) { return document; }
        public DynamicDocument toDynamicDocument(Document document) { return null; }
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/MongoQueryServiceInjection.java" \
    >"$TEMP_DIR/mongo-query-service-injection-java.out" 2>&1; then
    fail "Java external source unexpectedly injected arbitrary QueryService into AbstractMongoQueryService"
fi
grep -F "AbstractMongoQueryService" "$TEMP_DIR/mongo-query-service-injection-java.out" >/dev/null || {
    cat "$TEMP_DIR/mongo-query-service-injection-java.out" >&2
    fail "Java Mongo QueryService injection fixture failed for an unexpected reason"
}
echo "PASS: Java external source cannot inject arbitrary QueryService into AbstractMongoQueryService"

cat >"$TEMP_DIR/java/ElasticsearchQueryServiceInjection.java" <<'EOF'
package external.fixture;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.DynamicDocument;
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService;
import me.ahoo.wow.query.QueryService;
import me.ahoo.wow.query.converter.ConditionConverter;
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient;

public final class ElasticsearchQueryServiceInjection {
    static final class ElasticsearchBypass extends AbstractElasticsearchQueryService<Object> {
        ElasticsearchBypass(QueryService<Object> arbitrary) { super(arbitrary); }
        public NamedAggregate getNamedAggregate() { return null; }
        public ReactiveElasticsearchClient getElasticsearchClient() { return null; }
        public ConditionConverter<Query> getConditionConverter() { return null; }
        public String getIndexName() { return "index"; }
        public Object toTypedResult(DynamicDocument document) { return document; }
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/ElasticsearchQueryServiceInjection.java" \
    >"$TEMP_DIR/elasticsearch-query-service-injection-java.out" 2>&1; then
    fail "Java external source unexpectedly injected arbitrary QueryService into AbstractElasticsearchQueryService"
fi
grep -F "AbstractElasticsearchQueryService" "$TEMP_DIR/elasticsearch-query-service-injection-java.out" >/dev/null || {
    cat "$TEMP_DIR/elasticsearch-query-service-injection-java.out" >&2
    fail "Java Elasticsearch QueryService injection fixture failed for an unexpected reason"
}
echo "PASS: Java external source cannot inject arbitrary QueryService into AbstractElasticsearchQueryService"

cat >"$TEMP_DIR/kotlin/MongoQueryServiceInjection.kt" <<'EOF'
package external.fixture

import com.mongodb.reactivestreams.client.MongoCollection
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.mongo.query.AbstractMongoQueryService
import me.ahoo.wow.mongo.query.MongoProjectionConverter
import me.ahoo.wow.mongo.query.MongoSortConverter
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import org.bson.Document
import org.bson.conversions.Bson

class MongoBypass(arbitrary: QueryService<Any>) : AbstractMongoQueryService<Any>(arbitrary) {
    override val namedAggregate: NamedAggregate get() = error("unused")
    override val collection: MongoCollection<Document> get() = error("unused")
    override val converter: ConditionConverter<Bson> get() = error("unused")
    override val projectionConverter: MongoProjectionConverter get() = error("unused")
    override val sortConverter: MongoSortConverter get() = error("unused")
    override fun toTypedResult(document: Document): Any = document
    override fun toDynamicDocument(document: Document): DynamicDocument = error("unused")
}
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name mongo-query-service-injection-negative \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin" "$TEMP_DIR/kotlin/MongoQueryServiceInjection.kt" \
    >"$TEMP_DIR/mongo-query-service-injection-kotlin.out" 2>&1; then
    fail "Kotlin external source unexpectedly injected arbitrary QueryService into AbstractMongoQueryService"
fi
grep -F "AbstractMongoQueryService" "$TEMP_DIR/mongo-query-service-injection-kotlin.out" >/dev/null || {
    cat "$TEMP_DIR/mongo-query-service-injection-kotlin.out" >&2
    fail "Kotlin Mongo QueryService injection fixture failed for an unexpected reason"
}
echo "PASS: Kotlin external source cannot inject arbitrary QueryService into AbstractMongoQueryService"

cat >"$TEMP_DIR/kotlin/ElasticsearchQueryServiceInjection.kt" <<'EOF'
package external.fixture

import co.elastic.clients.elasticsearch._types.query_dsl.Query
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.query.QueryService
import me.ahoo.wow.query.converter.ConditionConverter
import org.springframework.data.elasticsearch.client.elc.ReactiveElasticsearchClient

class ElasticsearchBypass(arbitrary: QueryService<Any>) : AbstractElasticsearchQueryService<Any>(arbitrary) {
    override val namedAggregate: NamedAggregate get() = error("unused")
    override val elasticsearchClient: ReactiveElasticsearchClient get() = error("unused")
    override val conditionConverter: ConditionConverter<Query> get() = error("unused")
    override val indexName: String = "index"
    override fun toTypedResult(document: DynamicDocument): Any = document
}
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name elasticsearch-query-service-injection-negative \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin" "$TEMP_DIR/kotlin/ElasticsearchQueryServiceInjection.kt" \
    >"$TEMP_DIR/elasticsearch-query-service-injection-kotlin.out" 2>&1; then
    fail "Kotlin external source unexpectedly injected arbitrary QueryService into AbstractElasticsearchQueryService"
fi
grep -F "AbstractElasticsearchQueryService" "$TEMP_DIR/elasticsearch-query-service-injection-kotlin.out" >/dev/null || {
    cat "$TEMP_DIR/elasticsearch-query-service-injection-kotlin.out" >&2
    fail "Kotlin Elasticsearch QueryService injection fixture failed for an unexpected reason"
}
echo "PASS: Kotlin external source cannot inject arbitrary QueryService into AbstractElasticsearchQueryService"

cat >"$TEMP_DIR/java/LegacyStorageAbstractSubclass.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService;
import me.ahoo.wow.mongo.query.AbstractMongoQueryService;

abstract class LegacyMongoSubclass extends AbstractMongoQueryService<Object> {
    LegacyMongoSubclass() { super(); }
}

abstract class LegacyElasticsearchSubclass extends AbstractElasticsearchQueryService<Object> {
    LegacyElasticsearchSubclass() { super(); }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-storage-abstract-compatible" \
    "$TEMP_DIR/java/LegacyStorageAbstractSubclass.java"
echo "PASS: Java external legacy storage abstract subclasses remain source compatible"

if javac --release 17 -Xlint:deprecation -Werror -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-storage-abstract-deprecated" \
    "$TEMP_DIR/java/LegacyStorageAbstractSubclass.java" \
    >"$TEMP_DIR/java-storage-abstract-deprecated.out" 2>&1; then
    fail "Java legacy storage abstract no-arg constructors are not deprecated"
fi
for class_name in AbstractMongoQueryService AbstractElasticsearchQueryService; do
    grep -F "$class_name" "$TEMP_DIR/java-storage-abstract-deprecated.out" >/dev/null || {
        cat "$TEMP_DIR/java-storage-abstract-deprecated.out" >&2
        fail "Java legacy storage abstract deprecation fixture did not diagnose $class_name"
    }
done
echo "PASS: Java legacy storage abstract no-arg constructors are deprecated"

cat >"$TEMP_DIR/kotlin/LegacyStorageAbstractSubclass.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService
import me.ahoo.wow.mongo.query.AbstractMongoQueryService

abstract class LegacyMongoSubclass : AbstractMongoQueryService<Any>()
abstract class LegacyElasticsearchSubclass : AbstractElasticsearchQueryService<Any>()
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name storage-abstract-source-compatible \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-storage-abstract-compatible" \
    "$TEMP_DIR/kotlin/LegacyStorageAbstractSubclass.kt"
echo "PASS: Kotlin external legacy storage abstract subclasses remain source compatible"

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name storage-abstract-deprecated \
    -Werror -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-storage-abstract-deprecated" \
    "$TEMP_DIR/kotlin/LegacyStorageAbstractSubclass.kt" \
    >"$TEMP_DIR/kotlin-storage-abstract-deprecated.out" 2>&1; then
    fail "Kotlin legacy storage abstract no-arg constructors are not deprecated"
fi
for class_name in AbstractMongoQueryService AbstractElasticsearchQueryService; do
    grep -F "$class_name" "$TEMP_DIR/kotlin-storage-abstract-deprecated.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-storage-abstract-deprecated.out" >&2
        fail "Kotlin legacy storage abstract deprecation fixture did not diagnose $class_name"
    }
done
echo "PASS: Kotlin legacy storage abstract no-arg constructors are deprecated"

cat >"$TEMP_DIR/java/StableAdmissionSpi.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.query.invocation.QueryAdmission;
import me.ahoo.wow.query.invocation.QueryAdmissionContext;
import me.ahoo.wow.query.invocation.QueryAuthorityProvider;
import me.ahoo.wow.query.invocation.QueryAuthorityView;
import me.ahoo.wow.query.invocation.QueryInvocationScope;
import me.ahoo.wow.query.invocation.QueryProvenance;
import me.ahoo.wow.query.policy.CapabilityDecision;
import me.ahoo.wow.query.policy.QueryFieldAccess;
import me.ahoo.wow.query.policy.QueryPolicy;
import me.ahoo.wow.query.policy.QueryPolicyConstraints;
import me.ahoo.wow.query.policy.QueryPolicyContext;
import me.ahoo.wow.query.policy.QueryPolicyDeniedException;
import me.ahoo.wow.query.policy.QueryPolicyPermissions;
import me.ahoo.wow.query.policy.QueryPolicyResult;
import me.ahoo.wow.query.policy.QueryPolicyResultShape;
import me.ahoo.wow.cosec.query.CoSecQueryPolicy;
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry;
import me.ahoo.wow.query.mask.MaskingResultPolicy;
import me.ahoo.wow.query.mask.StateDataMaskerRegistry;
import me.ahoo.wow.query.policy.abac.AbacQueryPolicy;
import me.ahoo.wow.query.policy.abac.PrincipalTagResolver;
import me.ahoo.wow.query.policy.abac.PrincipalTagSchemaCustomizer;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StableAdmissionSpi {
    public static QueryPolicy policy() {
        return context -> Mono.just(new QueryPolicyResult());
    }

    public static Object[] securityPolicies() {
        PrincipalTagResolver tags = new PrincipalTagResolver(
            Set.of("department"),
            context -> Mono.just(Map.of("department", List.of("engineering")))
        );
        return new Object[]{
            new MaskingResultPolicy(new StateDataMaskerRegistry(), new EventStreamMaskerRegistry()),
            new AbacQueryPolicy(tags),
            new PrincipalTagSchemaCustomizer(tags),
            new CoSecQueryPolicy()
        };
    }

    public static Object[] use(
        QueryAdmission admission,
        QueryAdmissionContext context,
        QueryAuthorityProvider provider,
        QueryAuthorityView authority,
        QueryInvocationScope scope,
        QueryProvenance provenance,
        QueryPolicy policy,
        QueryPolicyContext policyContext,
        QueryPolicyResult policyResult,
        QueryPolicyConstraints policyConstraints,
        QueryFieldAccess fieldAccess,
        CapabilityDecision capabilityDecision,
        QueryPolicyDeniedException deniedException,
        QueryPolicyResultShape resultShape
    ) {
        return new Object[]{
            admission, context, provider, authority, scope, provenance,
            policy, policyContext, policyResult, policyConstraints, fieldAccess,
            capabilityDecision, deniedException, resultShape,
            QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS
        };
    }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/StableAdmissionSpi.java"
echo "PASS: Java external stable admission SPI source"

cat >"$TEMP_DIR/java/StableBackendSpi.java" <<'EOF'
package external.fixture;

import java.util.List;
import java.util.Map;
import java.util.Set;
import me.ahoo.wow.api.query.expression.MatchAll;
import me.ahoo.wow.api.query.expression.PortableOperator;
import me.ahoo.wow.api.query.expression.QueryCapabilityId;
import me.ahoo.wow.api.query.expression.LogicalField;
import me.ahoo.wow.api.query.expression.QueryValue;
import me.ahoo.wow.api.query.expression.RelativeTimeExpression;
import me.ahoo.wow.api.query.expression.RelativeTimeOperation;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.expression.QueryExpression;
import me.ahoo.wow.api.query.expression.StringComparisonMode;
import me.ahoo.wow.api.query.gateway.QueryDocumentKind;
import me.ahoo.wow.api.query.gateway.QueryPage;
import me.ahoo.wow.api.query.gateway.QuerySort;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.backend.QueryBackend;
import me.ahoo.wow.query.backend.QueryBackendDescriptor;
import me.ahoo.wow.query.backend.QueryBackendFactory;
import me.ahoo.wow.query.backend.QueryBackendReadiness;
import me.ahoo.wow.query.backend.QueryBackendResolutionContext;
import me.ahoo.wow.query.backend.QueryBackendResolver;
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity;
import me.ahoo.wow.query.backend.QueryPlanVersion;
import me.ahoo.wow.query.backend.QueryPortableFeature;
import me.ahoo.wow.query.backend.ResolvedQueryBackend;
import me.ahoo.wow.query.invocation.QueryProvenance;
import me.ahoo.wow.query.plan.CountQueryPlanV1;
import me.ahoo.wow.query.plan.ListQueryPlanV1;
import me.ahoo.wow.query.plan.PageQueryPlanV1;
import me.ahoo.wow.query.plan.QueryPlanResultShape;
import me.ahoo.wow.query.plan.QueryPlanV1;
import me.ahoo.wow.query.plan.SingleQueryPlanV1;
import me.ahoo.wow.query.schema.QuerySchema;
import me.ahoo.wow.query.validation.QueryBudgetLimit;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class StableBackendSpi implements QueryBackend {
    @Override
    public QueryBackendDescriptor getDescriptor() {
        return null;
    }

    @Override
    public <R> Mono<R> single(SingleQueryPlanV1<R> plan) {
        use(plan);
        return Mono.empty();
    }

    @Override
    public <R> Flux<R> list(ListQueryPlanV1<R> plan) {
        int ignored = plan.getLimit();
        use(plan);
        return Flux.empty();
    }

    @Override
    public <R> Mono<QueryPage<R>> page(PageQueryPlanV1<R> plan) {
        Object ignored = plan.getPage();
        use(plan);
        return Mono.empty();
    }

    @Override
    public Mono<Long> count(CountQueryPlanV1 plan) {
        use(plan);
        return Mono.just(0L);
    }

    @Override
    public Mono<QueryBackendReadiness> readiness() {
        return Mono.just(QueryBackendReadiness.Ready.INSTANCE);
    }

    public static QueryBackendDescriptor descriptor() {
        return new QueryBackendDescriptor(
            "fixture",
            Set.of(QueryDocumentKind.SNAPSHOT),
            Set.of(QueryPlanVersion.V1),
            Set.of(PortableOperator.EQ),
            Set.of(QueryPortableFeature.ELEMENT_MATCH),
            Set.of(StringComparisonMode.DEFAULT),
            Set.<QueryCapabilityId>of(),
            QueryBudgetLimit.UNBOUNDED
        );
    }

    public static QueryBackendResolver resolver(QueryBackend backend, QueryBackendRouteIdentity route) {
        return target -> ResolvedQueryBackend.resolve(backend, route);
    }

    public static QueryBackendResolver contextAwareResolver(
        QueryBackend backend,
        QueryBackendRouteIdentity route
    ) {
        return new QueryBackendResolver() {
            @Override
            public Mono<ResolvedQueryBackend> resolve(QueryTarget target) {
                return ResolvedQueryBackend.resolve(backend, route);
            }

            @Override
            public Mono<ResolvedQueryBackend> resolve(QueryBackendResolutionContext context) {
                return ResolvedQueryBackend.resolve(backend, route);
            }
        };
    }

    public static QueryBackendFactory factory(QueryBackend backend) {
        return context -> backend;
    }

    public static QueryBackendResolutionContext context(QueryTarget target, QuerySchema schema) {
        QueryBackendResolutionContext context = new QueryBackendResolutionContext(target, schema, MatchAll.INSTANCE);
        if (!context.copy(target, schema, MatchAll.INSTANCE).equals(context) ||
            context.component1() != target || context.component2() != schema || context.component3() != MatchAll.INSTANCE) {
            throw new AssertionError("Unexpected backend resolution context value semantics");
        }
        return context;
    }

    public static String backendId(QueryBackendDescriptor descriptor) {
        return descriptor.getBackendId();
    }

    public static Object[] use(QueryPlanV1 plan) {
        QueryPlanVersion version = plan.getVersion();
        QueryTarget target = plan.getTarget();
        QueryExpression expression = plan.getSecuredExpression();
        Map<QueryProvenance, QueryExpression> provenance = plan.getExpressionProvenance();
        QueryPlanResultShape shape = plan.getAuthorizedResultShape();
        List<QuerySort> sort = plan.getSort();
        Object deadline = plan.getEffectiveDeadline();
        QueryBudgetLimit budget = plan.getEffectiveBudget();
        String correlationId = plan.getCorrelationId();
        QueryBackendRouteIdentity route = plan.getRouteIdentity();
        return new Object[]{version, target, expression, provenance, shape, sort, deadline, budget, correlationId, route};
    }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/StableBackendSpi.java"
echo "PASS: Java external stable backend SPI source"

cat >"$TEMP_DIR/kotlin/StableAdmissionSpi.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.invocation.QueryAdmission
import me.ahoo.wow.query.invocation.QueryAdmissionContext
import me.ahoo.wow.query.invocation.QueryAuthorityProvider
import me.ahoo.wow.query.invocation.QueryAuthorityView
import me.ahoo.wow.query.invocation.QueryInvocationScope
import me.ahoo.wow.query.invocation.QueryProvenance
import me.ahoo.wow.query.policy.CapabilityDecision
import me.ahoo.wow.query.policy.QueryFieldAccess
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyConstraints
import me.ahoo.wow.query.policy.QueryPolicyContext
import me.ahoo.wow.query.policy.QueryPolicyDeniedException
import me.ahoo.wow.query.policy.QueryPolicyPermissions
import me.ahoo.wow.query.policy.QueryPolicyResult
import me.ahoo.wow.query.policy.QueryPolicyResultShape
import me.ahoo.wow.cosec.query.CoSecQueryPolicy
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.query.mask.MaskingResultPolicy
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.policy.abac.AbacQueryPolicy
import me.ahoo.wow.query.policy.abac.PrincipalTagResolver
import me.ahoo.wow.query.policy.abac.PrincipalTagSchemaCustomizer
import reactor.core.publisher.Mono

val stablePolicy = QueryPolicy { Mono.just(QueryPolicyResult()) }

@Suppress("DEPRECATION")
fun stableSecurityPolicies(): List<Any> {
    val tags = PrincipalTagResolver(setOf("department")) {
        Mono.just(mapOf("department" to listOf("engineering")))
    }
    return listOf(
        MaskingResultPolicy(StateDataMaskerRegistry(), EventStreamMaskerRegistry()),
        AbacQueryPolicy(tags),
        PrincipalTagSchemaCustomizer(tags),
        CoSecQueryPolicy()
    )
}

fun useStableAdmissionSpi(
    admission: QueryAdmission,
    context: QueryAdmissionContext,
    provider: QueryAuthorityProvider,
    authority: QueryAuthorityView,
    scope: QueryInvocationScope,
    provenance: QueryProvenance,
    policy: QueryPolicy,
    policyContext: QueryPolicyContext,
    policyResult: QueryPolicyResult,
    policyConstraints: QueryPolicyConstraints,
    fieldAccess: QueryFieldAccess,
    capabilityDecision: CapabilityDecision,
    deniedException: QueryPolicyDeniedException,
    resultShape: QueryPolicyResultShape
): List<Any> = listOf(
    admission,
    context,
    provider,
    authority,
    scope,
    provenance,
    policy,
    policyContext,
    policyResult,
    policyConstraints,
    fieldAccess,
    capabilityDecision,
    deniedException,
    resultShape,
    QueryPolicyPermissions.QUERY_DELETED_SNAPSHOTS
)
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-api-external-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin" \
    "$TEMP_DIR/kotlin/StableAdmissionSpi.kt"
echo "PASS: Kotlin external stable admission SPI source"

cat >"$TEMP_DIR/kotlin/StableBackendSpi.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.api.query.gateway.QueryPage
import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendDescriptor
import me.ahoo.wow.query.backend.QueryBackendFactory
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolutionContext
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.ResolvedQueryBackend
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.QueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import me.ahoo.wow.query.schema.QuerySchema
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class StableBackendSpi(override val descriptor: QueryBackendDescriptor) : QueryBackend {
    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.empty()
    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.empty()
    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.empty()
    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.just(0)
    override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)
}

fun stableResolver(backend: QueryBackend, route: QueryBackendRouteIdentity) =
    QueryBackendResolver { ResolvedQueryBackend.resolve(backend, route) }

fun contextAwareResolver(backend: QueryBackend, route: QueryBackendRouteIdentity) =
    object : QueryBackendResolver {
        override fun resolve(target: me.ahoo.wow.api.query.gateway.QueryTarget) =
            ResolvedQueryBackend.resolve(backend, route)

        override fun resolve(context: QueryBackendResolutionContext) =
            ResolvedQueryBackend.resolve(backend, route)
    }

fun stableBackendFactory(backend: QueryBackend) = QueryBackendFactory { backend }

fun consumeResolutionContext(context: QueryBackendResolutionContext): List<Any> {
    val copied = context.copy()
    val target = copied.component1()
    val schema: QuerySchema = copied.component2()
    val securedExpression = copied.component3()
    return listOf(target, schema, securedExpression)
}

fun consumePlan(plan: QueryPlanV1): List<Any?> = listOf(
    plan.version,
    plan.target,
    plan.securedExpression,
    plan.expressionProvenance,
    plan.authorizedResultShape,
    plan.sort,
    plan.effectiveDeadline,
    plan.effectiveBudget,
    plan.correlationId,
    plan.routeIdentity
)
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-backend-api-external-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin" \
    "$TEMP_DIR/kotlin/StableBackendSpi.kt"
echo "PASS: Kotlin external stable backend SPI source"

cat >"$TEMP_DIR/java/PrecompiledResolverBinaryProbe.java" <<'EOF'
package external.fixture;

import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.security.MessageDigest;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.expression.MatchAll;
import me.ahoo.wow.api.query.gateway.QueryDocumentKind;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.backend.QueryBackendResolutionContext;
import me.ahoo.wow.query.backend.QueryBackendResolver;
import me.ahoo.wow.query.schema.QuerySchema;
import reactor.core.publisher.Mono;

public final class PrecompiledResolverBinaryProbe {
    /*
     * external.fixture.PreChangeTargetOnlyResolver compiled with javac --release 17 against the exact
     * 4963c3f6422ff60958117d6a9de3aa1ce081ae1c wow-query JAR. Class SHA-256:
     * 85f9a3b52525d2d30a493fa7fbaeb1e251f70d20c3995d1a2b45dc70e1e84c46.
     */
    private static final String PRE_CHANGE_RESOLVER =
        "yv66vgAAAD0AIQoAAgADBwAEDAAFAAYBABBqYXZhL2xhbmcvT2JqZWN0AQAGPGluaXQ+AQADKClWCQAIAAkHAAoMAAsADAEALGV4dGVybmFsL2ZpeHR1cmUvUHJlQ2hhbmdlVGFyZ2V0T25seVJlc29sdmVyAQAFY2FsbHMBAAFJCQAIAA4MAA8AEAEADm9ic2VydmVkVGFyZ2V0AQArTG1lL2Fob28vd293L2FwaS9xdWVyeS9nYXRld2F5L1F1ZXJ5VGFyZ2V0OwoAEgATBwAUDAAVABYBABtyZWFjdG9yL2NvcmUvcHVibGlzaGVyL01vbm8BAAVlbXB0eQEAHygpTHJlYWN0b3IvY29yZS9wdWJsaXNoZXIvTW9ubzsHABgBAC5tZS9haG9vL3dvdy9xdWVyeS9iYWNrZW5kL1F1ZXJ5QmFja2VuZFJlc29sdmVyAQAEQ29kZQEAD0xpbmVOdW1iZXJUYWJsZQEAB3Jlc29sdmUBAEooTG1lL2Fob28vd293L2FwaS9xdWVyeS9nYXRld2F5L1F1ZXJ5VGFyZ2V0OylMcmVhY3Rvci9jb3JlL3B1Ymxpc2hlci9Nb25vOwEACVNpZ25hdHVyZQEAfChMbWUvYWhvby93b3cvYXBpL3F1ZXJ5L2dhdGV3YXkvUXVlcnlUYXJnZXQ7KUxyZWFjdG9yL2NvcmUvcHVibGlzaGVyL01vbm88TG1lL2Fob28vd293L3F1ZXJ5L2JhY2tlbmQvUmVzb2x2ZWRRdWVyeUJhY2tlbmQ7PjsBAApTb3VyY2VGaWxlAQAgUHJlQ2hhbmdlVGFyZ2V0T25seVJlc29sdmVyLmphdmEAMQAIAAIAAQAXAAIAAQALAAwAAAABAA8AEAAAAAIAAQAFAAYAAQAZAAAAHQABAAEAAAAFKrcAAbEAAAABABoAAAAGAAEAAAAIAAEAGwAcAAIAGQAAADMAAwACAAAAEypZtAAHBGC1AAcqK7UADbgAEbAAAAABABoAAAAOAAMAAAAOAAoADwAPABAAHQAAAAIAHgABAB8AAAACACA=";

    private PrecompiledResolverBinaryProbe() {
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(PRE_CHANGE_RESOLVER);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!digest.equals("85f9a3b52525d2d30a493fa7fbaeb1e251f70d20c3995d1a2b45dc70e1e84c46")) {
            throw new AssertionError("Pre-change resolver fixture digest changed");
        }
        Class<?> legacyType = new ClassLoader(PrecompiledResolverBinaryProbe.class.getClassLoader()) {
            Class<?> defineLegacy() {
                return defineClass("external.fixture.PreChangeTargetOnlyResolver", bytes, 0, bytes.length);
            }
        }.defineLegacy();
        Object legacy = legacyType.getConstructor().newInstance();
        QueryBackendResolver resolver = (QueryBackendResolver) legacy;
        NamedAggregate aggregate = new NamedAggregate() {
            @Override
            public String getContextName() {
                return "binary";
            }

            @Override
            public String getAggregateName() {
                return "probe";
            }
        };
        QueryTarget target = new QueryTarget(aggregate, QueryDocumentKind.SNAPSHOT);
        QueryBackendResolutionContext context = new QueryBackendResolutionContext(
            target,
            new QuerySchema(target, List.of()),
            MatchAll.INSTANCE
        );
        Mono<?> result = resolver.resolve(context);
        if (result == null || legacyType.getField("calls").getInt(legacy) != 1 ||
            legacyType.getField("observedTarget").get(legacy) != target) {
            throw new AssertionError("Pre-change target-only resolver was not invoked through the context default method");
        }
    }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/PrecompiledResolverBinaryProbe.java"
java -classpath "$TEMP_DIR/classes/java:$FIXTURE_CLASSPATH" external.fixture.PrecompiledResolverBinaryProbe
echo "PASS: Pre-change target-only resolver binary compatibility"

cat >"$TEMP_DIR/java/StableGatewayApi.java" <<'EOF'
package external.fixture;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import me.ahoo.wow.api.query.error.QueryErrorCode;
import me.ahoo.wow.api.query.error.QueryErrorReason;
import me.ahoo.wow.api.query.error.QueryException;
import me.ahoo.wow.api.query.error.QueryStage;
import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.expression.QueryCapabilityId;
import me.ahoo.wow.api.query.expression.LogicalField;
import me.ahoo.wow.api.query.expression.PortableOperator;
import me.ahoo.wow.api.query.expression.QueryValue;
import me.ahoo.wow.api.query.expression.RelativeTimeExpression;
import me.ahoo.wow.api.query.expression.RelativeTimeOperation;
import me.ahoo.wow.api.query.gateway.CountQueryRequest;
import me.ahoo.wow.api.query.gateway.ListQueryRequest;
import me.ahoo.wow.api.query.gateway.PageQueryRequest;
import me.ahoo.wow.api.query.gateway.QueryPage;
import me.ahoo.wow.api.query.gateway.QueryProjection;
import me.ahoo.wow.api.query.gateway.QueryResultShape;
import me.ahoo.wow.api.query.gateway.SingleQueryRequest;
import me.ahoo.wow.query.QueryGateway;
import me.ahoo.wow.query.QueryGatewayConfiguration;
import me.ahoo.wow.query.QueryGatewayFactory;
import me.ahoo.wow.query.backend.QueryBackendResolver;
import me.ahoo.wow.query.invocation.QueryAdmission;
import me.ahoo.wow.query.policy.QueryPolicy;
import me.ahoo.wow.query.policy.QueryPolicyRegistration;
import me.ahoo.wow.query.policy.QueryPolicyResult;
import me.ahoo.wow.query.policy.QueryPolicyResultShape;
import me.ahoo.wow.query.result.ResultPolicy;
import me.ahoo.wow.query.result.ResultPolicyContext;
import me.ahoo.wow.query.schema.QuerySchemaResolver;
import me.ahoo.wow.query.validation.QueryBudgetLimit;
import me.ahoo.wow.query.validation.QueryStructureLimits;
import me.ahoo.wow.query.event.GatewayEventStreamQueryService;
import me.ahoo.wow.query.event.GatewayEventStreamQueryServiceFactory;
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService;
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryServiceFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class StableGatewayApi {
    public static QueryGateway create(
        QueryAdmission admission,
        QuerySchemaResolver schemaResolver,
        QueryBackendResolver backendResolver
    ) {
        QueryPolicy policy = context -> Mono.just(new QueryPolicyResult());
        QueryGatewayConfiguration configuration = new QueryGatewayConfiguration(
            admission,
            schemaResolver,
            backendResolver,
            List.of(policy),
            List.<ResultPolicy>of(),
            Clock.systemUTC(),
            ZoneId.of("UTC"),
            new QueryStructureLimits(16, 128, 128, 4096),
            QueryBudgetLimit.UNBOUNDED,
            Set.<QueryCapabilityId>of(),
            null
        );
        QueryGatewayFactory.create(configuration);
        QueryPolicyRegistration registration = new QueryPolicyRegistration("fixture-policy", 10, policy);
        if (!registration.getDescriptorId().equals("fixture-policy") ||
            registration.getOrder() != 10 || registration.getPolicy() != policy) {
            throw new AssertionError("Unexpected query policy registration snapshot");
        }
        return QueryGatewayFactory.create(configuration, List.of(registration));
    }

    public static ResultPolicy resultPolicy() {
        return (context, value) -> Mono.just(value);
    }

    public static Object[] compatibilityApi(NamedAggregate aggregate, QueryGateway gateway) {
        PortableOperator emptyCollection = PortableOperator.EMPTY_COLLECTION;
        QueryResultShape.ProjectedDynamic projected = new QueryResultShape.ProjectedDynamic(
            QueryProjection.All.INSTANCE
        );
        RelativeTimeExpression relative = new RelativeTimeExpression(
            "eventTime",
            RelativeTimeOperation.RECENT_DAYS,
            List.of(new QueryValue.IntegerValue(3)),
            "UTC"
        );
        RelativeTimeExpression copy = relative.copy(
            relative.getField(), relative.getOperation(), relative.getOperands(), relative.getZoneId()
        );
        return new Object[]{
            emptyCollection,
            projected,
            new QueryPolicyResultShape.ProjectedDynamic(QueryProjection.All.INSTANCE),
            copy,
            new GatewaySnapshotQueryService<Object>(aggregate, gateway),
            new GatewaySnapshotQueryServiceFactory(gateway),
            new GatewayEventStreamQueryService(aggregate, gateway),
            new GatewayEventStreamQueryServiceFactory(gateway)
        };
    }

    public static QueryErrorCode useQueryExceptionConstructors() {
        QueryException legacy = new QueryException(
            QueryErrorCode.BACKEND_FAILURE,
            QueryStage.EXECUTION,
            QueryErrorReason.BACKEND_EXECUTION_FAILED
        );
        QueryException incomplete = new QueryException(
            QueryErrorCode.INCOMPLETE_RESULT,
            QueryStage.EXECUTION,
            QueryErrorReason.INCOMPLETE_STREAM,
            legacy.getCode()
        );
        return incomplete.getCauseCode();
    }

    public static Object[] use(
        QueryGateway gateway,
        SingleQueryRequest<String> single,
        ListQueryRequest<String> list,
        PageQueryRequest<String> page,
        CountQueryRequest count,
        ResultPolicyContext context
    ) {
        Mono<String> singleResult = gateway.single(single);
        Flux<String> listResult = gateway.list(list);
        Mono<QueryPage<String>> pageResult = gateway.page(page);
        Mono<Long> countResult = gateway.count(count);
        return new Object[]{singleResult, listResult, pageResult, countResult, context.getBackendId()};
    }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/StableGatewayApi.java"
echo "PASS: Java external stable query gateway API source"

cat >"$TEMP_DIR/kotlin/StableGatewayApi.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.api.query.gateway.CountQueryRequest
import me.ahoo.wow.api.query.gateway.ListQueryRequest
import me.ahoo.wow.api.query.gateway.PageQueryRequest
import me.ahoo.wow.api.query.gateway.QueryProjection
import me.ahoo.wow.api.query.gateway.QueryResultShape
import me.ahoo.wow.api.query.gateway.SingleQueryRequest
import me.ahoo.wow.api.query.expression.LogicalField
import me.ahoo.wow.api.query.expression.QueryValue
import me.ahoo.wow.api.query.expression.RelativeTimeExpression
import me.ahoo.wow.api.query.expression.RelativeTimeOperation
import me.ahoo.wow.api.query.expression.PortableOperator
import me.ahoo.wow.api.query.expression.PredicateExpression
import me.ahoo.wow.api.modeling.NamedAggregate
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.QueryGatewayConfiguration
import me.ahoo.wow.query.QueryGatewayFactory
import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyRegistration
import me.ahoo.wow.query.policy.QueryPolicyResultShape
import me.ahoo.wow.query.result.ResultPolicy
import me.ahoo.wow.query.event.GatewayEventStreamQueryService
import me.ahoo.wow.query.event.GatewayEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService
import me.ahoo.wow.query.snapshot.GatewaySnapshotQueryServiceFactory
import me.ahoo.wow.api.query.error.QueryErrorCode
import me.ahoo.wow.api.query.error.QueryErrorReason
import me.ahoo.wow.api.query.error.QueryException
import me.ahoo.wow.api.query.error.QueryStage
import reactor.core.publisher.Mono

val stableResultPolicy = ResultPolicy { _, value -> Mono.just(value) }

val legacyQueryException = QueryException(
    QueryErrorCode.BACKEND_FAILURE,
    QueryStage.EXECUTION,
    QueryErrorReason.BACKEND_EXECUTION_FAILED
)

val incompleteQueryException = QueryException(
    QueryErrorCode.INCOMPLETE_RESULT,
    QueryStage.EXECUTION,
    QueryErrorReason.INCOMPLETE_STREAM,
    legacyQueryException.code
)

fun compatibilityApi(aggregate: NamedAggregate, gateway: QueryGateway): List<Any> {
    val projected = QueryResultShape.ProjectedDynamic(
        QueryProjection.Include(setOf(LogicalField("state.id")))
    )
    val emptyCollection = PredicateExpression(
        LogicalField("labels"),
        PortableOperator.EMPTY_COLLECTION,
        emptyList()
    )
    val relative = RelativeTimeExpression(
        "eventTime",
        RelativeTimeOperation.RECENT_DAYS,
        listOf(QueryValue.IntegerValue(3)),
        "UTC"
    )
    return listOf(
        emptyCollection,
        projected,
        QueryPolicyResultShape.ProjectedDynamic(QueryProjection.All),
        relative.copy(operands = relative.operands),
        GatewaySnapshotQueryService<Any>(aggregate, gateway),
        GatewaySnapshotQueryServiceFactory(gateway),
        GatewayEventStreamQueryService(aggregate, gateway),
        GatewayEventStreamQueryServiceFactory(gateway)
    )
}

fun createStableGateway(configuration: QueryGatewayConfiguration): QueryGateway =
    QueryGatewayFactory.create(configuration)

fun createRegistrationAwareGateway(
    configuration: QueryGatewayConfiguration,
    policy: QueryPolicy
): QueryGateway {
    val registration = QueryPolicyRegistration("fixture-policy", 10, policy)
    check(
        registration.descriptorId == "fixture-policy" &&
            registration.order == 10 && registration.policy === policy
    )
    return QueryGatewayFactory.create(configuration, listOf(registration))
}

fun useStableGateway(
    gateway: QueryGateway,
    single: SingleQueryRequest<String>,
    list: ListQueryRequest<String>,
    page: PageQueryRequest<String>,
    count: CountQueryRequest
) = listOf(
    gateway.single(single),
    gateway.list(list),
    gateway.page(page),
    gateway.count(count)
)
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-gateway-api-external-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin" \
    "$TEMP_DIR/kotlin/StableGatewayApi.kt"
echo "PASS: Kotlin external stable query gateway API source"

cat >"$TEMP_DIR/kotlin/DeprecatedLegacyQueryFactories.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.event.AbstractEventStreamQueryServiceFactory
import me.ahoo.wow.query.event.EventStreamQueryServiceFactory
import me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory
import me.ahoo.wow.query.event.RoutingEventStreamQueryServiceFactory
import me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.RoutingSnapshotQueryServiceFactory
import me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory

fun retainedLegacyQueryFactories(
    snapshotRouting: RoutingSnapshotQueryServiceFactory,
    eventRouting: RoutingEventStreamQueryServiceFactory
): List<Any> = listOf(
    SnapshotQueryServiceFactory::class,
    AbstractSnapshotQueryServiceFactory::class,
    NoOpSnapshotQueryServiceFactory,
    snapshotRouting,
    EventStreamQueryServiceFactory::class,
    AbstractEventStreamQueryServiceFactory::class,
    NoOpEventStreamQueryServiceFactory,
    eventRouting
)
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-legacy-factories-source-compatible-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-legacy-factories-compatible" \
    "$TEMP_DIR/kotlin/DeprecatedLegacyQueryFactories.kt"
echo "PASS: Kotlin external legacy query factories remain source compatible"

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-legacy-factories-deprecated-fixture \
    -Werror -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-legacy-factories-deprecated" \
    "$TEMP_DIR/kotlin/DeprecatedLegacyQueryFactories.kt" \
    >"$TEMP_DIR/kotlin-legacy-factories-deprecated.out" 2>&1; then
    fail "Kotlin external source unexpectedly used legacy query factories without deprecation diagnostics"
fi
grep -F "deprecated" "$TEMP_DIR/kotlin-legacy-factories-deprecated.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-legacy-factories-deprecated.out" >&2
    fail "Kotlin legacy query factory fixture did not report deprecation diagnostics"
}

for factory_class in \
    me.ahoo.wow.query.snapshot.SnapshotQueryServiceFactory \
    me.ahoo.wow.query.snapshot.AbstractSnapshotQueryServiceFactory \
    me.ahoo.wow.query.snapshot.NoOpSnapshotQueryServiceFactory \
    me.ahoo.wow.query.snapshot.RoutingSnapshotQueryServiceFactory \
    me.ahoo.wow.query.event.EventStreamQueryServiceFactory \
    me.ahoo.wow.query.event.AbstractEventStreamQueryServiceFactory \
    me.ahoo.wow.query.event.NoOpEventStreamQueryServiceFactory \
    me.ahoo.wow.query.event.RoutingEventStreamQueryServiceFactory; do
    javap -classpath "$WOW_QUERY_JAR" -v "$factory_class" | grep -F "Deprecated: true" >/dev/null ||
        fail "Published legacy query factory is missing the JVM Deprecated attribute: $factory_class"
done
echo "PASS: Published legacy query factories are deprecated"

cat >"$TEMP_DIR/kotlin/DeprecatedLegacyQuerySecurity.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.cosec.query.CoSecRewriteRequestCondition
import me.ahoo.wow.query.mask.AbstractDataMaskerRegistry
import me.ahoo.wow.query.mask.AggregateDataMasker
import me.ahoo.wow.query.mask.AggregateDynamicDocumentMasker
import me.ahoo.wow.query.mask.DataMasker
import me.ahoo.wow.query.mask.DataMaskerRegistry
import me.ahoo.wow.query.mask.DataMasking
import me.ahoo.wow.query.mask.DefaultAggregateDataMasker
import me.ahoo.wow.query.mask.DynamicDocumentMasker
import me.ahoo.wow.query.mask.EventStreamDynamicDocumentMasker
import me.ahoo.wow.query.mask.EventStreamMaskerRegistry
import me.ahoo.wow.query.mask.StateDataMaskerRegistry
import me.ahoo.wow.query.mask.StateDynamicDocumentMasker
import me.ahoo.wow.spring.boot.starter.cosec.CoSecAutoConfiguration
import me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration

fun retainedLegacyQuerySecurityTypes(): List<Any> = listOf(
    DataMasker::class,
    DynamicDocumentMasker::class,
    AggregateDynamicDocumentMasker::class,
    StateDynamicDocumentMasker::class,
    EventStreamDynamicDocumentMasker::class,
    AggregateDataMasker::class,
    DefaultAggregateDataMasker::class,
    DataMaskerRegistry::class,
    AbstractDataMaskerRegistry::class,
    StateDataMaskerRegistry::class,
    EventStreamMaskerRegistry::class,
    DataMasking::class,
    CoSecRewriteRequestCondition,
    CoSecAutoConfiguration().coSecRewriteRequestCondition(),
    QueryAutoConfiguration().stateDataMaskerRegistry(emptyList()),
    QueryAutoConfiguration().eventStreamMaskerRegistry(emptyList())
)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-legacy-security-deprecated-fixture \
    -Werror -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-legacy-security-deprecated" \
    "$TEMP_DIR/kotlin/DeprecatedLegacyQuerySecurity.kt" \
    >"$TEMP_DIR/kotlin-legacy-security-deprecated.out" 2>&1; then
    fail "Kotlin external source unexpectedly used legacy query security APIs without deprecation diagnostics"
fi
for legacy_security_name in \
    DataMasker StateDataMaskerRegistry CoSecRewriteRequestCondition \
    coSecRewriteRequestCondition stateDataMaskerRegistry; do
    grep -F "$legacy_security_name" "$TEMP_DIR/kotlin-legacy-security-deprecated.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-legacy-security-deprecated.out" >&2
        fail "Kotlin legacy query security fixture did not diagnose $legacy_security_name"
    }
done

for masker_class in \
    DataMasker DynamicDocumentMasker AggregateDynamicDocumentMasker StateDynamicDocumentMasker \
    EventStreamDynamicDocumentMasker AggregateDataMasker DefaultAggregateDataMasker DataMaskerRegistry \
    AbstractDataMaskerRegistry StateDataMaskerRegistry EventStreamMaskerRegistry DataMasking; do
    javap -classpath "$WOW_QUERY_JAR" -v "me.ahoo.wow.query.mask.$masker_class" |
        grep -F "Deprecated: true" >/dev/null ||
        fail "Published legacy masker is missing the JVM Deprecated attribute: $masker_class"
done
[[ "$(javap -classpath "$WOW_QUERY_JAR" -v me.ahoo.wow.query.mask.AggregateDataMaskerKt | grep -c 'Deprecated: true')" -eq 1 ]] ||
    fail "Published AggregateDataMasker extensions have incomplete JVM deprecation metadata"
[[ "$(javap -classpath "$WOW_QUERY_JAR" -v me.ahoo.wow.query.mask.DataMaskingKt | grep -c 'Deprecated: true')" -eq 3 ]] ||
    fail "Published DataMasking extensions have incomplete JVM deprecation metadata"
javap -classpath "$RUNTIME_CLASSPATH" -v me.ahoo.wow.cosec.query.CoSecRewriteRequestCondition |
    grep -F "Deprecated: true" >/dev/null ||
    fail "Published CoSec rewrite is missing the JVM Deprecated attribute"
javap -classpath "$WOW_STARTER_JAR:$RUNTIME_CLASSPATH" -v \
    me.ahoo.wow.spring.boot.starter.cosec.CoSecAutoConfiguration |
    sed -n '/public .* coSecRewriteRequestCondition();/,/^  }/p' |
    grep -F 'Deprecated: true' >/dev/null ||
    fail "Published CoSec rewrite registration is missing the JVM Deprecated attribute"
[[ "$(javap -classpath "$WOW_STARTER_JAR:$RUNTIME_CLASSPATH" -v \
    me.ahoo.wow.spring.boot.starter.query.QueryAutoConfiguration | grep -c 'Deprecated: true')" -eq 2 ]] ||
    fail "Published legacy masker registration methods have incomplete JVM deprecation metadata"
echo "PASS: Published legacy masker, registration and CoSec rewrite APIs are deprecated"

cat >"$TEMP_DIR/kotlin/DeprecatedLegacyWebFluxRewrite.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration
import me.ahoo.wow.webflux.route.query.AbstractRewriteRequestCondition
import me.ahoo.wow.webflux.route.query.DefaultRewriteRequestCondition
import me.ahoo.wow.webflux.route.query.RewriteRequestCondition

fun retainedLegacyWebFluxRewrite(): List<Any> = listOf(
    RewriteRequestCondition::class,
    AbstractRewriteRequestCondition::class,
    DefaultRewriteRequestCondition,
    WebFluxAutoConfiguration().rewriteRequestCondition()
)
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-legacy-webflux-rewrite-source-compatible-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-legacy-webflux-rewrite-compatible" \
    "$TEMP_DIR/kotlin/DeprecatedLegacyWebFluxRewrite.kt"
echo "PASS: Kotlin external legacy WebFlux rewrite APIs remain source compatible"

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-legacy-webflux-rewrite-deprecated-fixture \
    -Werror -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-legacy-webflux-rewrite-deprecated" \
    "$TEMP_DIR/kotlin/DeprecatedLegacyWebFluxRewrite.kt" \
    >"$TEMP_DIR/kotlin-legacy-webflux-rewrite-deprecated.out" 2>&1; then
    fail "Kotlin external source unexpectedly used legacy WebFlux rewrite APIs without deprecation diagnostics"
fi
for legacy_webflux_name in \
    RewriteRequestCondition AbstractRewriteRequestCondition DefaultRewriteRequestCondition \
    rewriteRequestCondition; do
    grep -F "$legacy_webflux_name" "$TEMP_DIR/kotlin-legacy-webflux-rewrite-deprecated.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-legacy-webflux-rewrite-deprecated.out" >&2
        fail "Kotlin legacy WebFlux rewrite fixture did not diagnose $legacy_webflux_name"
    }
done

for rewrite_class in \
    me.ahoo.wow.webflux.route.query.RewriteRequestCondition \
    me.ahoo.wow.webflux.route.query.AbstractRewriteRequestCondition \
    me.ahoo.wow.webflux.route.query.DefaultRewriteRequestCondition; do
    javap -classpath "$RUNTIME_CLASSPATH" -v "$rewrite_class" | grep -F "Deprecated: true" >/dev/null ||
        fail "Published legacy WebFlux rewrite type is missing the JVM Deprecated attribute: $rewrite_class"
done
[[ "$(javap -classpath "$WOW_STARTER_JAR:$RUNTIME_CLASSPATH" -v \
    me.ahoo.wow.spring.boot.starter.webflux.WebFluxAutoConfiguration | grep -c 'Deprecated: true')" -eq 1 ]] ||
    fail "Published WebFlux rewrite registration is missing the JVM Deprecated attribute"
echo "PASS: Published legacy WebFlux rewrite types and registration are deprecated"

if jar tf "$WOW_QUERY_JAR" | grep -E \
    'me/ahoo/wow/query/compat/Legacy(QueryRequest|SnapshotResult|EventResult|QueryError)Mapper([$.]|\.class)' \
    >"$TEMP_DIR/legacy-gateway-mapper-entries.out"; then
    cat "$TEMP_DIR/legacy-gateway-mapper-entries.out" >&2
    fail "Published wow-query JAR exposes legacy Gateway mapper class entries"
fi
echo "PASS: Published wow-query JAR exposes no legacy Gateway mapper class entries"

cat >"$TEMP_DIR/java/InternalLegacyGatewayAdapters.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.api.modeling.NamedAggregate;
import me.ahoo.wow.api.query.Condition;
import me.ahoo.wow.api.query.DynamicDocument;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.compat.LegacyEventResultMapper;
import me.ahoo.wow.query.compat.LegacyQueryErrorMapper;
import me.ahoo.wow.query.compat.LegacyQueryRequestMapper;
import me.ahoo.wow.query.compat.LegacySnapshotResultMapper;
import me.ahoo.wow.query.expression.LegacyConditionLowerer;

public final class InternalLegacyGatewayAdapters {
    public static Object[] access(
        QueryTarget target,
        NamedAggregate aggregate,
        DynamicDocument document,
        Condition condition
    ) {
        return new Object[]{
            new LegacyQueryRequestMapper(target),
            new LegacySnapshotResultMapper<>(aggregate),
            LegacyEventResultMapper.INSTANCE.map(document),
            LegacyQueryErrorMapper.INSTANCE,
            LegacyConditionLowerer.INSTANCE
                .lowerForGateway$me_ahoo_wow_wow_query(condition, target)
        };
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-legacy-adapters-negative" \
    "$TEMP_DIR/java/InternalLegacyGatewayAdapters.java" \
    >"$TEMP_DIR/java-legacy-adapters-negative.out" 2>&1; then
    fail "Java external source unexpectedly accessed legacy Gateway adapters"
fi
for adapter_name in LegacyQueryRequestMapper LegacySnapshotResultMapper \
    LegacyEventResultMapper LegacyQueryErrorMapper lowerForGateway; do
    grep -F "$adapter_name" "$TEMP_DIR/java-legacy-adapters-negative.out" >/dev/null || {
        cat "$TEMP_DIR/java-legacy-adapters-negative.out" >&2
        fail "Java legacy adapter negative fixture did not diagnose $adapter_name"
    }
done
echo "PASS: Java external source cannot access legacy Gateway adapters"

cat >"$TEMP_DIR/kotlin/InternalLegacyGatewayAdapters.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.compat.LegacyEventResultMapper
import me.ahoo.wow.query.compat.LegacyQueryErrorMapper
import me.ahoo.wow.query.compat.LegacyQueryRequestMapper
import me.ahoo.wow.query.compat.LegacySnapshotResultMapper
import me.ahoo.wow.query.expression.LegacyConditionLowerer

fun internalLegacyGatewayAdapters(): List<Any> = listOf(
    LegacyQueryRequestMapper::class,
    LegacySnapshotResultMapper::class,
    LegacyEventResultMapper::class,
    LegacyQueryErrorMapper::class,
    LegacyConditionLowerer::lowerForGateway
)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-legacy-adapters-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-legacy-adapters-negative" \
    "$TEMP_DIR/kotlin/InternalLegacyGatewayAdapters.kt" \
    >"$TEMP_DIR/kotlin-legacy-adapters-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly accessed legacy Gateway adapters"
fi
for adapter_name in LegacyQueryRequestMapper LegacySnapshotResultMapper \
    LegacyEventResultMapper LegacyQueryErrorMapper lowerForGateway; do
    grep -F "$adapter_name" "$TEMP_DIR/kotlin-legacy-adapters-negative.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-legacy-adapters-negative.out" >&2
        fail "Kotlin legacy adapter negative fixture did not diagnose $adapter_name"
    }
done
echo "PASS: Kotlin external source cannot access legacy Gateway adapters"

cat >"$TEMP_DIR/java/ExternallyCallableLegacyGatewayAdapters.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.api.query.DynamicDocument;
import me.ahoo.wow.api.query.ISingleQuery;
import me.ahoo.wow.query.compat.LegacyEventResultMapper;
import me.ahoo.wow.query.compat.LegacyQueryRequestMapper;
import me.ahoo.wow.query.compat.LegacySnapshotResultMapper;

public final class ExternallyCallableLegacyGatewayAdapters {
    public static Object[] call(
        LegacyQueryRequestMapper requests,
        LegacySnapshotResultMapper<?> snapshots,
        LegacyEventResultMapper events,
        ISingleQuery query,
        DynamicDocument document
    ) {
        return new Object[]{
            requests.single(query),
            snapshots.map(document),
            events.map(document)
        };
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-legacy-adapters-call-negative" \
    "$TEMP_DIR/java/ExternallyCallableLegacyGatewayAdapters.java" \
    >"$TEMP_DIR/java-legacy-adapters-call-negative.out" 2>&1; then
    fail "Java external source unexpectedly called legacy Gateway adapters passed as parameters"
fi
for adapter_name in LegacyQueryRequestMapper LegacySnapshotResultMapper LegacyEventResultMapper; do
    grep -F "$adapter_name" "$TEMP_DIR/java-legacy-adapters-call-negative.out" >/dev/null || {
        cat "$TEMP_DIR/java-legacy-adapters-call-negative.out" >&2
        fail "Java callable legacy adapter fixture did not diagnose $adapter_name"
    }
done
echo "PASS: Java external source cannot call legacy Gateway adapters passed as parameters"

cat >"$TEMP_DIR/java/InternalGatewayCompatibilityFunctions.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.api.query.Condition;
import me.ahoo.wow.api.query.DynamicDocument;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.compat.LegacyRequestCompatibilityKt;
import me.ahoo.wow.query.compat.LegacyResultCompatibilityKt;

public final class InternalGatewayCompatibilityFunctions {
    public static Object[] access(
        QueryTarget target,
        Condition condition,
        DynamicDocument document
    ) {
        return new Object[]{
            LegacyRequestCompatibilityKt.legacyCountRequest(target, condition),
            LegacyResultCompatibilityKt.materializeLegacyEvent(document),
            LegacyResultCompatibilityKt.adaptLegacySnapshotDocument(document),
            LegacyResultCompatibilityKt.adaptLegacyEventDocument(document)
        };
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-gateway-compatibility-negative" \
    "$TEMP_DIR/java/InternalGatewayCompatibilityFunctions.java" \
    >"$TEMP_DIR/java-gateway-compatibility-negative.out" 2>&1; then
    fail "Java external source unexpectedly accessed Gateway compatibility functions"
fi
for function_name in legacyCountRequest materializeLegacyEvent adaptLegacySnapshotDocument adaptLegacyEventDocument; do
    grep -F "$function_name" "$TEMP_DIR/java-gateway-compatibility-negative.out" >/dev/null || {
        cat "$TEMP_DIR/java-gateway-compatibility-negative.out" >&2
        fail "Java Gateway compatibility negative fixture did not diagnose $function_name"
    }
done
echo "PASS: Java external source cannot access Gateway compatibility functions"

cat >"$TEMP_DIR/kotlin/InternalGatewayCompatibilityFunctions.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.api.query.Condition
import me.ahoo.wow.api.query.DynamicDocument
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.compat.legacyCountRequest
import me.ahoo.wow.query.compat.adaptLegacyEventDocument
import me.ahoo.wow.query.compat.adaptLegacySnapshotDocument
import me.ahoo.wow.query.compat.materializeLegacyEvent

fun internalGatewayCompatibilityFunctions(
    target: QueryTarget,
    condition: Condition,
    document: DynamicDocument
): List<Any> = listOf(
    legacyCountRequest(target, condition),
    materializeLegacyEvent(document),
    adaptLegacySnapshotDocument(document),
    adaptLegacyEventDocument(document)
)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-gateway-compatibility-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-gateway-compatibility-negative" \
    "$TEMP_DIR/kotlin/InternalGatewayCompatibilityFunctions.kt" \
    >"$TEMP_DIR/kotlin-gateway-compatibility-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly accessed Gateway compatibility functions"
fi
for function_name in legacyCountRequest materializeLegacyEvent adaptLegacySnapshotDocument adaptLegacyEventDocument; do
    grep -F "$function_name" "$TEMP_DIR/kotlin-gateway-compatibility-negative.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-gateway-compatibility-negative.out" >&2
        fail "Kotlin Gateway compatibility negative fixture did not diagnose $function_name"
    }
done
echo "PASS: Kotlin external source cannot access Gateway compatibility functions"

cat >"$TEMP_DIR/java/InternalLegacyQueryGatewayExecution.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.api.query.IListQuery;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.QueryGateway;
import me.ahoo.wow.query.compat.LegacyQueryGatewayExecution;

public final class InternalLegacyQueryGatewayExecution {
    public static Object call(QueryGateway gateway, QueryTarget target, IListQuery query) {
        return LegacyQueryGatewayExecution.INSTANCE.list(gateway, target, query, query);
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-legacy-query-execution-negative" \
    "$TEMP_DIR/java/InternalLegacyQueryGatewayExecution.java" \
    >"$TEMP_DIR/java-legacy-query-execution-negative.out" 2>&1; then
    fail "Java external source unexpectedly accessed LegacyQueryGatewayExecution"
fi
grep -F "list" "$TEMP_DIR/java-legacy-query-execution-negative.out" >/dev/null || {
    cat "$TEMP_DIR/java-legacy-query-execution-negative.out" >&2
    fail "Java LegacyQueryGatewayExecution negative fixture did not diagnose list"
}
echo "PASS: Java external source cannot call LegacyQueryGatewayExecution"

cat >"$TEMP_DIR/kotlin/InternalLegacyQueryGatewayExecution.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.api.query.IListQuery
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.QueryGateway
import me.ahoo.wow.query.compat.LegacyQueryGatewayExecution

fun internalLegacyQueryGatewayExecution(gateway: QueryGateway, target: QueryTarget, query: IListQuery): Any =
    LegacyQueryGatewayExecution.list(gateway, target, query, query)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-legacy-execution-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-legacy-query-execution-negative" \
    "$TEMP_DIR/kotlin/InternalLegacyQueryGatewayExecution.kt" \
    >"$TEMP_DIR/kotlin-legacy-query-execution-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly accessed LegacyQueryGatewayExecution"
fi
grep -F "LegacyQueryGatewayExecution" "$TEMP_DIR/kotlin-legacy-query-execution-negative.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-legacy-query-execution-negative.out" >&2
    fail "Kotlin LegacyQueryGatewayExecution negative fixture did not diagnose the bridge"
}
echo "PASS: Kotlin external source cannot call LegacyQueryGatewayExecution"

cat >"$TEMP_DIR/java/InternalGatewayNormalization.java" <<'EOF'
package external.fixture;

import java.time.Instant;
import java.time.ZoneId;
import me.ahoo.wow.api.query.Condition;
import me.ahoo.wow.api.query.expression.QueryExpression;
import me.ahoo.wow.api.query.expression.RelativeTimeExpression;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.expression.InvocationExpressionNormalizer;
import me.ahoo.wow.query.expression.LegacyConditionLowering;
import me.ahoo.wow.query.expression.RelativeTimeExpressionNormalizer;

public final class InternalGatewayNormalization {
    public static Object[] access(
        QueryExpression expression,
        RelativeTimeExpression relative,
        Condition condition,
        QueryTarget target
    ) {
        return new Object[]{
            InvocationExpressionNormalizer.INSTANCE.normalize(expression, Instant.EPOCH, ZoneId.of("UTC")),
            LegacyConditionLowering.INSTANCE.lowerForGateway$me_ahoo_wow_wow_query(condition, target),
            RelativeTimeExpressionNormalizer.INSTANCE.lower(relative, Instant.EPOCH, ZoneId.of("UTC"))
        };
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-gateway-normalization-negative" \
    "$TEMP_DIR/java/InternalGatewayNormalization.java" \
    >"$TEMP_DIR/java-gateway-normalization-negative.out" 2>&1; then
    fail "Java external source unexpectedly accessed Gateway normalization internals"
fi
for internal_name in InvocationExpressionNormalizer LegacyConditionLowering \
    RelativeTimeExpressionNormalizer; do
    grep -F "$internal_name" "$TEMP_DIR/java-gateway-normalization-negative.out" >/dev/null || {
        cat "$TEMP_DIR/java-gateway-normalization-negative.out" >&2
        fail "Java Gateway normalization negative fixture did not diagnose $internal_name"
    }
done
echo "PASS: Java external source cannot access Gateway normalization internals"

cat >"$TEMP_DIR/kotlin/InternalGatewayNormalization.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.expression.InvocationExpressionNormalizer
import me.ahoo.wow.query.expression.LegacyConditionLowering
import me.ahoo.wow.query.expression.RelativeTimeExpressionNormalizer

fun gatewayNormalizationInternals(): List<Any> = listOf(
    InvocationExpressionNormalizer,
    LegacyConditionLowering,
    RelativeTimeExpressionNormalizer
)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-gateway-normalization-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-gateway-normalization-negative" \
    "$TEMP_DIR/kotlin/InternalGatewayNormalization.kt" \
    >"$TEMP_DIR/kotlin-gateway-normalization-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly accessed Gateway normalization internals"
fi
for internal_name in InvocationExpressionNormalizer LegacyConditionLowering \
    RelativeTimeExpressionNormalizer; do
    grep -F "$internal_name" "$TEMP_DIR/kotlin-gateway-normalization-negative.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-gateway-normalization-negative.out" >&2
        fail "Kotlin Gateway normalization negative fixture did not diagnose $internal_name"
    }
done
echo "PASS: Kotlin external source cannot access Gateway normalization internals"

for facade_class in \
    me.ahoo.wow.query.snapshot.GatewaySnapshotQueryService \
    me.ahoo.wow.query.snapshot.GatewaySnapshotQueryServiceFactory \
    me.ahoo.wow.query.event.GatewayEventStreamQueryService \
    me.ahoo.wow.query.event.GatewayEventStreamQueryServiceFactory; do
    javap -classpath "$FIXTURE_CLASSPATH" -p -v "$facade_class" >"$TEMP_DIR/facade-javap.out"
    if grep -E 'me/ahoo/wow/query/(DefaultQueryGateway|plan/|backend/)' \
        "$TEMP_DIR/facade-javap.out" >/dev/null; then
        grep -E 'me/ahoo/wow/query/(DefaultQueryGateway|plan/|backend/)' \
            "$TEMP_DIR/facade-javap.out" >&2
        fail "Compatibility facade leaked query implementation dependencies: $facade_class"
    fi
done
echo "PASS: Gateway compatibility facades depend on public QueryGateway only"

assert_abstract_gateway_delegation() {
    local jar="$1"
    local class_name="$2"
    local forbidden="$3"
    local disassembly="$TEMP_DIR/$(basename "$jar").abstract-query-service.javap"
    javap -classpath "$jar:$RUNTIME_CLASSPATH" -p -c "$class_name" >"$disassembly"
    for method in single dynamicSingle list dynamicList paged dynamicPaged count; do
        local method_body="$TEMP_DIR/$(basename "$jar").$method.javap"
        awk -v method="$method" '
            $0 ~ "^  public .* " method "\\(" { capture = 1; next }
            capture && $0 ~ "^  (public|protected|private) " { exit }
            capture { print }
        ' "$disassembly" >"$method_body"
        grep -F "InterfaceMethod me/ahoo/wow/query/QueryService.$method:" "$method_body" >/dev/null || {
            cat "$method_body" >&2
            fail "$class_name.$method does not delegate through QueryService"
        }
        if grep -E "$forbidden" "$method_body" >/dev/null; then
            grep -E "$forbidden" "$method_body" >&2
            fail "$class_name.$method references a storage driver path"
        fi
    done
}

assert_abstract_gateway_delegation \
    "$WOW_MONGO_JAR" \
    me.ahoo.wow.mongo.query.AbstractMongoQueryService \
    'com/mongodb|org/bson|MongoCollectionsKt|findDocument|getCollection|getConverter|getProjectionConverter|getSortConverter'
assert_abstract_gateway_delegation \
    "$WOW_ELASTICSEARCH_JAR" \
    me.ahoo.wow.elasticsearch.query.AbstractElasticsearchQueryService \
    'co/elastic|org/springframework/data/elasticsearch|getElasticsearchClient|getConditionConverter|getIndexName|toTypedResult'
echo "PASS: Published storage abstract query methods delegate without driver references"

cat >"$TEMP_DIR/kotlin/InternalAdmissionImplementations.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.invocation.QueryDeadline
import me.ahoo.wow.query.invocation.QueryDeadlineExceededException
import me.ahoo.wow.query.invocation.QueryDeadlineGuard
import me.ahoo.wow.query.invocation.QueryInvocation
import me.ahoo.wow.query.invocation.QueryInvocationFactory
import me.ahoo.wow.query.invocation.QueryInvocationSeed
import me.ahoo.wow.query.policy.CombinedQueryPolicyResult
import me.ahoo.wow.query.policy.DefaultQueryPolicyChain
import me.ahoo.wow.query.policy.SystemQueryPolicy
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.immutableSnapshot
import me.ahoo.wow.query.DefaultQueryGateway
import me.ahoo.wow.query.DefaultQueryGatewayFactory
import me.ahoo.wow.query.QueryGatewayStage
import me.ahoo.wow.query.QueryGatewayStageObserver
import me.ahoo.wow.query.metrics.QueryGatewayMetricState
import me.ahoo.wow.query.metrics.QueryGatewayMetrics
import me.ahoo.wow.query.result.DefaultResultPolicyChain

fun internalImplementations(): List<Class<*>> = listOf(
    QueryDeadline::class.java,
    QueryDeadlineExceededException::class.java,
    QueryDeadlineGuard::class.java,
    QueryInvocation::class.java,
    QueryInvocationFactory::class.java,
    QueryInvocationSeed::class.java,
    CombinedQueryPolicyResult::class.java,
    DefaultQueryPolicyChain::class.java,
    SystemQueryPolicy::class.java,
    DefaultQueryGateway::class.java,
    DefaultQueryGatewayFactory::class.java,
    QueryGatewayStage::class.java,
    QueryGatewayStageObserver::class.java,
    QueryGatewayMetricState::class.java,
    QueryGatewayMetrics::class.java,
    DefaultResultPolicyChain::class.java
)

fun snapshotSchema(schema: QuerySchemaView) = schema.immutableSnapshot()
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-api-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-negative" \
    "$TEMP_DIR/kotlin/InternalAdmissionImplementations.kt" \
    >"$TEMP_DIR/kotlin-negative.out" 2>&1; then
    cat "$TEMP_DIR/kotlin-negative.out" >&2
    fail "Kotlin external source unexpectedly accessed internal admission implementations"
fi

for class_name in QueryDeadline QueryDeadlineExceededException QueryDeadlineGuard \
    QueryInvocation QueryInvocationFactory QueryInvocationSeed \
    CombinedQueryPolicyResult DefaultQueryPolicyChain SystemQueryPolicy \
    DefaultQueryGateway DefaultQueryGatewayFactory QueryGatewayStage QueryGatewayStageObserver \
    QueryGatewayMetricState QueryGatewayMetrics \
    DefaultResultPolicyChain; do
    grep -F "$class_name" "$TEMP_DIR/kotlin-negative.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-negative.out" >&2
        fail "Kotlin negative fixture did not diagnose $class_name"
    }
done
grep -F "immutableSnapshot" "$TEMP_DIR/kotlin-negative.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-negative.out" >&2
    fail "Kotlin negative fixture did not diagnose immutableSnapshot"
}
grep -F "internal" "$TEMP_DIR/kotlin-negative.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-negative.out" >&2
    fail "Kotlin negative fixture did not enforce internal visibility"
}
echo "PASS: Kotlin external internal admission implementation boundary"

cat >"$TEMP_DIR/java/ExternalPlannerConstruction.java" <<'EOF'
package external.fixture;

import java.util.Set;
import me.ahoo.wow.query.plan.DefaultQueryPlanner;

public final class ExternalPlannerConstruction {
    public static DefaultQueryPlanner construct() {
        return new DefaultQueryPlanner(Set.of());
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-planner-construction-negative" \
    "$TEMP_DIR/java/ExternalPlannerConstruction.java" \
    >"$TEMP_DIR/java-planner-construction-negative.out" 2>&1; then
    fail "Java external source unexpectedly constructed the internal query planner"
fi
grep -F 'DefaultQueryPlanner' "$TEMP_DIR/java-planner-construction-negative.out" >/dev/null || {
    cat "$TEMP_DIR/java-planner-construction-negative.out" >&2
    fail "Java planner construction fixture did not diagnose DefaultQueryPlanner"
}
echo "PASS: Java external query planner construction boundary"

cat >"$TEMP_DIR/java/ExternalPlannerFactory.java" <<'EOF'
package external.fixture;

import java.util.Set;
import me.ahoo.wow.query.plan.DefaultQueryPlanner;

public final class ExternalPlannerFactory {
    public static DefaultQueryPlanner construct() {
        return DefaultQueryPlanner.Companion.create$me_ahoo_wow_wow_query(Set.of());
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-planner-factory-negative" \
    "$TEMP_DIR/java/ExternalPlannerFactory.java" \
    >"$TEMP_DIR/java-planner-factory-negative.out" 2>&1; then
    fail "Java external source unexpectedly invoked the internal query planner factory"
fi
# shellcheck disable=SC2016 # The dollar sign is part of Kotlin's JVM method name.
grep -F 'create$me_ahoo_wow_wow_query' "$TEMP_DIR/java-planner-factory-negative.out" >/dev/null || {
    cat "$TEMP_DIR/java-planner-factory-negative.out" >&2
    fail "Java planner factory fixture did not diagnose the synthetic factory"
}
echo "PASS: Java external query planner factory boundary"

cat >"$TEMP_DIR/java/ExternalPlannerInvocation.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.query.plan.DefaultQueryPlanner;

public final class ExternalPlannerInvocation {
    public static Object invoke(DefaultQueryPlanner planner) {
        return planner.plan(null, null, null);
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-planner-invocation-negative" \
    "$TEMP_DIR/java/ExternalPlannerInvocation.java" \
    >"$TEMP_DIR/java-planner-invocation-negative.out" 2>&1; then
    fail "Java external source unexpectedly invoked the internal query planner"
fi
grep -F 'plan' "$TEMP_DIR/java-planner-invocation-negative.out" >/dev/null || {
    cat "$TEMP_DIR/java-planner-invocation-negative.out" >&2
    fail "Java planner invocation fixture did not diagnose plan"
}
echo "PASS: Java external query planner invocation boundary"

javap -classpath "$FIXTURE_CLASSPATH" -p -v \
    me.ahoo.wow.query.plan.DefaultQueryPlanner >"$TEMP_DIR/planner-javap.out"
awk '
    /private me\.ahoo\.wow\.query\.plan\.DefaultQueryPlanner\(/ { constructor = 1; next }
    constructor && /flags:/ { if ($0 ~ /ACC_PRIVATE/) private_constructor = 1; constructor = 0 }
    /public final reactor\.core\.publisher\.Mono plan[$]/ {
        plan = 1
        next
    }
    plan && /flags:/ { if ($0 ~ /ACC_SYNTHETIC/) synthetic_plan = 1; plan = 0 }
    END { exit !(private_constructor && synthetic_plan) }
' "$TEMP_DIR/planner-javap.out" || {
    grep -E -A2 'DefaultQueryPlanner\(| plan[$]' "$TEMP_DIR/planner-javap.out" >&2 || true
    fail "Query planner constructor or invocation method is Java-visible"
}
echo "PASS: JVM query planner construction and invocation boundary"

# shellcheck disable=SC2016 # The dollar sign is part of Kotlin's companion class name.
javap -classpath "$FIXTURE_CLASSPATH" -p -v \
    'me.ahoo.wow.query.plan.DefaultQueryPlanner$Companion' >"$TEMP_DIR/planner-companion-javap.out"
awk '
    /public final me\.ahoo\.wow\.query\.plan\.DefaultQueryPlanner create[$]/ { factory = 1; next }
    factory && /flags:/ { if ($0 ~ /ACC_SYNTHETIC/) synthetic_factory = 1; factory = 0 }
    END { exit !synthetic_factory }
' "$TEMP_DIR/planner-companion-javap.out" || {
    grep -E -A2 ' create[$]' "$TEMP_DIR/planner-companion-javap.out" >&2 || true
    fail "Query planner factory is Java-visible"
}
echo "PASS: JVM query planner factory boundary"

cat >"$TEMP_DIR/java/ExternalResolvedBackendForgery.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.query.backend.QueryBackend;
import me.ahoo.wow.query.backend.QueryBackendReadiness;
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity;
import me.ahoo.wow.query.backend.ResolvedQueryBackend;

public final class ExternalResolvedBackendForgery {
    public static ResolvedQueryBackend forge(QueryBackend backend, QueryBackendRouteIdentity route) {
        return new ResolvedQueryBackend(
            backend,
            backend.getDescriptor(),
            route,
            QueryBackendReadiness.Ready.INSTANCE
        );
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-resolved-forgery-negative" \
    "$TEMP_DIR/java/ExternalResolvedBackendForgery.java" \
    >"$TEMP_DIR/java-resolved-forgery-negative.out" 2>&1; then
    fail "Java external source unexpectedly forged a resolved backend readiness snapshot"
fi
echo "PASS: Java external resolved backend snapshot boundary"

cat >"$TEMP_DIR/kotlin/ExternalResolvedBackendForgery.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.backend.QueryBackend
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.ResolvedQueryBackend

fun forge(backend: QueryBackend, route: QueryBackendRouteIdentity): ResolvedQueryBackend =
    ResolvedQueryBackend(backend, backend.descriptor, route, QueryBackendReadiness.Ready)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-resolved-backend-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-resolved-forgery-negative" \
    "$TEMP_DIR/kotlin/ExternalResolvedBackendForgery.kt" \
    >"$TEMP_DIR/kotlin-resolved-forgery-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly forged a resolved backend readiness snapshot"
fi
echo "PASS: Kotlin external resolved backend snapshot boundary"

cat >"$TEMP_DIR/java/ExternalPlanImplementation.java" <<'EOF'
package external.fixture;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import me.ahoo.wow.api.query.expression.QueryExpression;
import me.ahoo.wow.api.query.gateway.QuerySort;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity;
import me.ahoo.wow.query.backend.QueryPlanVersion;
import me.ahoo.wow.query.invocation.QueryProvenance;
import me.ahoo.wow.query.plan.CountQueryPlanV1;
import me.ahoo.wow.query.plan.QueryPlanResultShape;
import me.ahoo.wow.query.validation.QueryBudgetLimit;

public final class ExternalPlanImplementation implements CountQueryPlanV1 {
    public QueryPlanVersion getVersion() { return null; }
    public QueryTarget getTarget() { return null; }
    public QueryExpression getSecuredExpression() { return null; }
    public Map<QueryProvenance, QueryExpression> getExpressionProvenance() { return null; }
    public QueryPlanResultShape getAuthorizedResultShape() { return null; }
    public List<QuerySort> getSort() { return null; }
    public Instant getEffectiveDeadline() { return null; }
    public QueryBudgetLimit getEffectiveBudget() { return null; }
    public String getCorrelationId() { return null; }
    public QueryBackendRouteIdentity getRouteIdentity() { return null; }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-negative" "$TEMP_DIR/java/ExternalPlanImplementation.java" \
    >"$TEMP_DIR/java-plan-negative.out" 2>&1; then
    cat "$TEMP_DIR/java-plan-negative.out" >&2
    fail "Java external source unexpectedly implemented a sealed query plan"
fi
echo "PASS: Java external sealed plan implementation boundary"

cat >"$TEMP_DIR/kotlin/ExternalPlanImplementation.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.api.query.expression.MatchAll
import me.ahoo.wow.api.query.gateway.QueryTarget
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity
import me.ahoo.wow.query.backend.QueryPlanVersion
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.QueryPlanResultShape
import me.ahoo.wow.query.validation.QueryBudgetLimit

class ExternalPlanImplementation(
    override val target: QueryTarget,
    override val routeIdentity: QueryBackendRouteIdentity
) : CountQueryPlanV1 {
    override val version = QueryPlanVersion.V1
    override val securedExpression = MatchAll
    override val expressionProvenance = emptyMap<me.ahoo.wow.query.invocation.QueryProvenance, me.ahoo.wow.api.query.expression.QueryExpression>()
    override val authorizedResultShape = QueryPlanResultShape.Count
    override val sort = emptyList<me.ahoo.wow.api.query.gateway.QuerySort>()
    override val effectiveDeadline = null
    override val effectiveBudget = QueryBudgetLimit.UNBOUNDED
    override val correlationId = "external"
}
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name query-plan-api-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-plan-negative" \
    "$TEMP_DIR/kotlin/ExternalPlanImplementation.kt" \
    >"$TEMP_DIR/kotlin-plan-negative.out" 2>&1; then
    cat "$TEMP_DIR/kotlin-plan-negative.out" >&2
    fail "Kotlin external source unexpectedly implemented a sealed query plan"
fi
echo "PASS: Kotlin external sealed plan implementation boundary"

for plan_class in \
    'me.ahoo.wow.query.plan.QueryPlanV1' \
    'me.ahoo.wow.query.plan.SingleQueryPlanV1' \
    'me.ahoo.wow.query.plan.ListQueryPlanV1' \
    'me.ahoo.wow.query.plan.PageQueryPlanV1' \
    'me.ahoo.wow.query.plan.CountQueryPlanV1'; do
    javap -classpath "$FIXTURE_CLASSPATH" -public "$plan_class" >"$TEMP_DIR/plan-javap.out"
    grep -F 'interface' "$TEMP_DIR/plan-javap.out" >/dev/null ||
        fail "Query plan consumer is not an interface: $plan_class"
    if grep -E ' copy\(| builder\(| of\(| create\(' "$TEMP_DIR/plan-javap.out" >/dev/null; then
        cat "$TEMP_DIR/plan-javap.out" >&2
        fail "Query plan consumer exposes a public construction method: $plan_class"
    fi
done
echo "PASS: Query plan consumers expose no public constructor, builder, factory, or copy"

cat >"$TEMP_DIR/kotlin/ExternalMongoBackendInternals.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.mongo.query.backend.MongoQueryBackend
import me.ahoo.wow.mongo.query.backend.MongoQueryFieldBinding
import me.ahoo.wow.mongo.query.backend.MongoQueryPlanCompiler
import me.ahoo.wow.mongo.query.backend.MongoQueryPublisherObserver
import me.ahoo.wow.mongo.query.backend.MongoQueryPublisherObservers
import me.ahoo.wow.mongo.query.backend.MongoQueryReadiness
import me.ahoo.wow.mongo.query.backend.MongoQueryReadinessRequirements
import me.ahoo.wow.mongo.query.backend.MongoQueryResultDecoder
import me.ahoo.wow.mongo.query.backend.mongoQueryBackendDescriptor

fun useMongoInternals(
    backend: MongoQueryBackend,
    compiler: MongoQueryPlanCompiler,
    binding: MongoQueryFieldBinding,
    observer: MongoQueryPublisherObserver,
    observers: MongoQueryPublisherObservers,
    readiness: MongoQueryReadiness,
    requirements: MongoQueryReadinessRequirements,
    decoder: MongoQueryResultDecoder
): List<Any> = listOf(
    backend,
    compiler,
    binding,
    observer,
    observers,
    readiness,
    requirements,
    decoder,
    ::mongoQueryBackendDescriptor
)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name mongo-query-api-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-mongo-internal-negative" \
    "$TEMP_DIR/kotlin/ExternalMongoBackendInternals.kt" \
    >"$TEMP_DIR/kotlin-mongo-internal-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly accessed Mongo backend internals"
fi
grep -F "internal" "$TEMP_DIR/kotlin-mongo-internal-negative.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-mongo-internal-negative.out" >&2
    fail "Kotlin Mongo internal fixture did not enforce internal visibility"
}
for class_name in MongoQueryBackend MongoQueryFieldBinding MongoQueryPlanCompiler \
    MongoQueryPublisherObserver MongoQueryPublisherObservers MongoQueryReadiness \
    MongoQueryReadinessRequirements MongoQueryResultDecoder mongoQueryBackendDescriptor; do
    grep -F "$class_name" "$TEMP_DIR/kotlin-mongo-internal-negative.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-mongo-internal-negative.out" >&2
        fail "Kotlin Mongo negative fixture did not diagnose $class_name"
    }
done
echo "PASS: Kotlin external Mongo bound backend and compiler boundary"

cat >"$TEMP_DIR/kotlin/ExternalElasticsearchBackendInternals.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.elasticsearch.eventsourcing.ElasticsearchQueryPresenceEncoder
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchMappingFieldRequirement
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchMappingUsage
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchIndexMappingSnapshot
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryBackend
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryBackendBinder
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryFieldBinding
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryMappingGuard
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryMappingSnapshot
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryOperation
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryOperationContext
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryPlanCompiler
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryPresenceBinding
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryPublisherObserver
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryPublisherObservers
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryReadiness
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryReadinessRequirements
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryResultDecoder
import me.ahoo.wow.elasticsearch.query.backend.ElasticsearchQueryTransport
import me.ahoo.wow.elasticsearch.query.backend.PitSearchAfterExecutor
import me.ahoo.wow.elasticsearch.query.backend.PitSearchAfterTransport
import me.ahoo.wow.elasticsearch.query.backend.elasticsearchQueryBackendDescriptor

fun useElasticsearchInternals(
    fieldRequirement: ElasticsearchMappingFieldRequirement,
    mappingUsage: ElasticsearchMappingUsage,
    indexMappingSnapshot: ElasticsearchIndexMappingSnapshot,
    backend: ElasticsearchQueryBackend,
    binder: ElasticsearchQueryBackendBinder,
    binding: ElasticsearchQueryFieldBinding,
    mappingGuard: ElasticsearchQueryMappingGuard,
    mappingSnapshot: ElasticsearchQueryMappingSnapshot,
    operation: ElasticsearchQueryOperation,
    operationContext: ElasticsearchQueryOperationContext,
    compiler: ElasticsearchQueryPlanCompiler,
    presence: ElasticsearchQueryPresenceBinding,
    observer: ElasticsearchQueryPublisherObserver,
    observers: ElasticsearchQueryPublisherObservers,
    readiness: ElasticsearchQueryReadiness,
    requirements: ElasticsearchQueryReadinessRequirements,
    decoder: ElasticsearchQueryResultDecoder,
    transport: ElasticsearchQueryTransport,
    pitTransport: PitSearchAfterTransport<*>,
    pitExecutor: PitSearchAfterExecutor<*>
): List<Any> = listOf(
    fieldRequirement, mappingUsage, indexMappingSnapshot, backend, binder, binding, mappingGuard, mappingSnapshot,
    operation, operationContext, compiler, presence, observer, observers, readiness, requirements,
    decoder, transport, pitTransport, pitExecutor, ElasticsearchQueryPresenceEncoder,
    ::elasticsearchQueryBackendDescriptor
)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name elasticsearch-query-api-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-elasticsearch-internal-negative" \
    "$TEMP_DIR/kotlin/ExternalElasticsearchBackendInternals.kt" \
    >"$TEMP_DIR/kotlin-elasticsearch-internal-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly accessed Elasticsearch backend internals"
fi
grep -F "internal" "$TEMP_DIR/kotlin-elasticsearch-internal-negative.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-elasticsearch-internal-negative.out" >&2
    fail "Kotlin Elasticsearch internal fixture did not enforce internal visibility"
}
for class_name in ElasticsearchQueryPresenceEncoder ElasticsearchMappingFieldRequirement ElasticsearchMappingUsage \
    ElasticsearchIndexMappingSnapshot \
    ElasticsearchQueryBackend ElasticsearchQueryBackendBinder ElasticsearchQueryFieldBinding \
    ElasticsearchQueryMappingGuard ElasticsearchQueryMappingSnapshot ElasticsearchQueryOperation \
    ElasticsearchQueryOperationContext ElasticsearchQueryPlanCompiler ElasticsearchQueryPresenceBinding \
    ElasticsearchQueryPublisherObserver \
    ElasticsearchQueryPublisherObservers ElasticsearchQueryReadiness ElasticsearchQueryReadinessRequirements \
    ElasticsearchQueryResultDecoder ElasticsearchQueryTransport PitSearchAfterTransport PitSearchAfterExecutor \
    elasticsearchQueryBackendDescriptor; do
    grep -F "$class_name" "$TEMP_DIR/kotlin-elasticsearch-internal-negative.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-elasticsearch-internal-negative.out" >&2
        fail "Kotlin Elasticsearch negative fixture did not diagnose $class_name"
    }
done
echo "PASS: Kotlin external Elasticsearch bound backend, compiler, PIT and encoder boundary"

cat >"$TEMP_DIR/java/InternalStarterRoutingApi.java" <<'EOF'
package external.fixture;

import java.util.List;
import java.util.Map;
import me.ahoo.wow.api.query.gateway.QueryDocumentKind;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchQueryBackendBindingConfiguration;
import me.ahoo.wow.spring.boot.starter.eventsourcing.StorageType;
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.CanonicalStorageRouteConfiguration;
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendBinding;
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendRouteSnapshot;
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendSelection;
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ResolvedStorageChannelRoute;
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ResolvedStorageRouteSnapshot;
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRouteCoordinator;
import me.ahoo.wow.spring.boot.starter.mongo.MongoQueryBackendBindingConfiguration;
import me.ahoo.wow.spring.boot.starter.query.StorageRoutingQueryBackendConfiguration;
import me.ahoo.wow.spring.boot.starter.query.StorageRoutingQueryBackendResolver;

public final class InternalStarterRoutingApi {
    public static QueryBackendBinding constructBinding() {
        return new QueryBackendBinding("mongo-event-store", QueryDocumentKind.EVENT_STREAM, StorageType.MONGO, null);
    }

    public static Object leakBinding(QueryBackendBinding binding) {
        return binding.getName();
    }

    public static Object leakSelection(QueryBackendSelection selection) {
        return selection.getBinding();
    }

    public static Object leakChannel(ResolvedStorageChannelRoute.Event event) {
        return event.getBindingName();
    }

    public static Object leakBackendRoutes(QueryBackendRouteSnapshot routes, QueryTarget target) {
        return routes.selection(target);
    }

    public static Object leakSnapshot(ResolvedStorageRouteSnapshot snapshot) {
        return snapshot.eventRoutes();
    }

    public static Object leakCoordinator(StorageRouteCoordinator coordinator) {
        return coordinator.resolve(null);
    }

    public static Object leakCanonical(
        CanonicalStorageRouteConfiguration configuration,
        ResolvedStorageRouteSnapshot snapshot
    ) {
        return configuration.queryBackendRouteSnapshot(snapshot);
    }

    public static Object leakQueryConfiguration(
        StorageRoutingQueryBackendConfiguration configuration,
        QueryBackendRouteSnapshot routes
    ) {
        return configuration.storageRoutingQueryBackendResolver(routes);
    }

    public static Object leakResolver(StorageRoutingQueryBackendResolver resolver, QueryTarget target) {
        return resolver.resolve(target);
    }

    public static Object leakMongo(MongoQueryBackendBindingConfiguration configuration) {
        return configuration.mongoEventQueryBackendBinding(null, null);
    }

    public static Object leakElasticsearch(ElasticsearchQueryBackendBindingConfiguration configuration) {
        return configuration.elasticsearchEventQueryBackendBinding(null);
    }

    public static Object constructInternals(QueryBackendSelection selection) {
        ResolvedStorageChannelRoute.Event event = new ResolvedStorageChannelRoute.Event(
            "mongo-event-store", StorageType.MONGO, null, null, selection
        );
        new QueryBackendRouteSnapshot(Map.of(), Map.of());
        new StorageRoutingQueryBackendConfiguration();
        new StorageRoutingQueryBackendResolver(null);
        new MongoQueryBackendBindingConfiguration(null);
        new ElasticsearchQueryBackendBindingConfiguration();
        new StorageRouteCoordinator(
            "context", false, List.of(), List.of(), List.of(), List.of(), List.of(),
            StorageType.MONGO, StorageType.MONGO
        );
        return new ResolvedStorageRouteSnapshot(event, null, Map.of());
    }
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java" "$TEMP_DIR/java/InternalStarterRoutingApi.java" \
    >"$TEMP_DIR/internal-starter-routing.out" 2>&1; then
    fail "Java external source unexpectedly compiled internal starter routing API"
fi
for class_name in QueryBackendBinding QueryBackendSelection ResolvedStorageChannelRoute \
    QueryBackendRouteSnapshot ResolvedStorageRouteSnapshot StorageRouteCoordinator \
    CanonicalStorageRouteConfiguration StorageRoutingQueryBackendConfiguration \
    StorageRoutingQueryBackendResolver MongoQueryBackendBindingConfiguration \
    ElasticsearchQueryBackendBindingConfiguration; do
    grep -F "$class_name" "$TEMP_DIR/internal-starter-routing.out" >/dev/null || {
        cat "$TEMP_DIR/internal-starter-routing.out" >&2
        fail "Java starter routing negative fixture did not diagnose $class_name"
    }
done
for method_name in getName getBinding getBindingName selection eventRoutes resolve \
    queryBackendRouteSnapshot storageRoutingQueryBackendResolver \
    mongoEventQueryBackendBinding elasticsearchEventQueryBackendBinding; do
    grep -F "$method_name" "$TEMP_DIR/internal-starter-routing.out" >/dev/null || {
        cat "$TEMP_DIR/internal-starter-routing.out" >&2
        fail "Java starter routing negative fixture did not diagnose $method_name"
    }
done
echo "PASS: Java external starter routing boundary"

cat >"$TEMP_DIR/kotlin/ExternalStarterRoutingInternals.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.spring.boot.starter.elasticsearch.ElasticsearchQueryBackendBindingConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.CanonicalStorageRouteConfiguration
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendBinding
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendRouteSnapshot
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.QueryBackendSelection
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ResolvedStorageChannelRoute
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.ResolvedStorageRouteSnapshot
import me.ahoo.wow.spring.boot.starter.eventsourcing.routing.StorageRouteCoordinator
import me.ahoo.wow.spring.boot.starter.mongo.MongoQueryBackendBindingConfiguration
import me.ahoo.wow.spring.boot.starter.query.StorageRoutingQueryBackendConfiguration
import me.ahoo.wow.spring.boot.starter.query.StorageRoutingQueryBackendResolver

fun useStarterRoutingInternals(
    backendBinding: QueryBackendBinding,
    backendRoutes: QueryBackendRouteSnapshot,
    selection: QueryBackendSelection,
    route: ResolvedStorageChannelRoute,
    snapshot: ResolvedStorageRouteSnapshot,
    coordinator: StorageRouteCoordinator,
    canonicalConfiguration: CanonicalStorageRouteConfiguration,
    resolverConfiguration: StorageRoutingQueryBackendConfiguration,
    resolver: StorageRoutingQueryBackendResolver,
    mongoConfiguration: MongoQueryBackendBindingConfiguration,
    elasticsearchConfiguration: ElasticsearchQueryBackendBindingConfiguration,
): List<Any> = listOf(
    backendBinding,
    backendRoutes,
    selection,
    route,
    snapshot,
    coordinator,
    canonicalConfiguration,
    resolverConfiguration,
    resolver,
    mongoConfiguration,
    elasticsearchConfiguration,
)
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name starter-query-routing-external-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-starter-routing-negative" \
    "$TEMP_DIR/kotlin/ExternalStarterRoutingInternals.kt" \
    >"$TEMP_DIR/kotlin-starter-routing-negative.out" 2>&1; then
    fail "Kotlin external source unexpectedly accessed starter routing internals"
fi
grep -F "internal" "$TEMP_DIR/kotlin-starter-routing-negative.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-starter-routing-negative.out" >&2
    fail "Kotlin starter routing fixture did not enforce internal visibility"
}
for class_name in QueryBackendBinding QueryBackendRouteSnapshot QueryBackendSelection \
    ResolvedStorageChannelRoute ResolvedStorageRouteSnapshot StorageRouteCoordinator \
    CanonicalStorageRouteConfiguration StorageRoutingQueryBackendConfiguration \
    StorageRoutingQueryBackendResolver MongoQueryBackendBindingConfiguration \
    ElasticsearchQueryBackendBindingConfiguration; do
    grep -F "$class_name" "$TEMP_DIR/kotlin-starter-routing-negative.out" >/dev/null || {
        cat "$TEMP_DIR/kotlin-starter-routing-negative.out" >&2
        fail "Kotlin starter routing negative fixture did not diagnose $class_name"
    }
done
echo "PASS: Kotlin external starter routing boundary"

cat >"$TEMP_DIR/java/RemovedSnapshotQueryFilter.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter;

public interface RemovedSnapshotQueryFilter extends SnapshotQueryFilter {
}
EOF

if javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-removed-snapshot-query-filter" \
    "$TEMP_DIR/java/RemovedSnapshotQueryFilter.java" \
    >"$TEMP_DIR/java-removed-snapshot-query-filter.out" 2>&1; then
    fail "Java external source unexpectedly compiled removed SnapshotQueryFilter"
fi
grep -F "SnapshotQueryFilter" "$TEMP_DIR/java-removed-snapshot-query-filter.out" >/dev/null || {
    cat "$TEMP_DIR/java-removed-snapshot-query-filter.out" >&2
    fail "Java removed SnapshotQueryFilter fixture failed for an unexpected reason"
}

cat >"$TEMP_DIR/kotlin/RemovedSnapshotQueryFilter.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.snapshot.filter.SnapshotQueryFilter

interface RemovedSnapshotQueryFilter : SnapshotQueryFilter
EOF

if java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name removed-snapshot-query-filter-negative-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-removed-snapshot-query-filter" \
    "$TEMP_DIR/kotlin/RemovedSnapshotQueryFilter.kt" \
    >"$TEMP_DIR/kotlin-removed-snapshot-query-filter.out" 2>&1; then
    fail "Kotlin external source unexpectedly compiled removed SnapshotQueryFilter"
fi
grep -F "SnapshotQueryFilter" "$TEMP_DIR/kotlin-removed-snapshot-query-filter.out" >/dev/null || {
    cat "$TEMP_DIR/kotlin-removed-snapshot-query-filter.out" >&2
    fail "Kotlin removed SnapshotQueryFilter fixture failed for an unexpected reason"
}
echo "PASS: Removed SnapshotQueryFilter is a compile-time break"

cat >"$TEMP_DIR/java/MigratedQueryPolicy.java" <<'EOF'
package external.fixture;

import me.ahoo.wow.query.policy.QueryPolicy;
import me.ahoo.wow.query.policy.QueryPolicyResult;
import reactor.core.publisher.Mono;

public final class MigratedQueryPolicy {
    public static QueryPolicy create() {
        return context -> Mono.just(new QueryPolicyResult());
    }
}
EOF

javac --release 17 -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/java-migrated-query-policy" \
    "$TEMP_DIR/java/MigratedQueryPolicy.java"

cat >"$TEMP_DIR/kotlin/MigratedQueryPolicy.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.policy.QueryPolicy
import me.ahoo.wow.query.policy.QueryPolicyResult
import reactor.core.publisher.Mono

val migratedQueryPolicy = QueryPolicy { Mono.just(QueryPolicyResult()) }
EOF

java -cp "$KOTLIN_COMPILER_CLASSPATH" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -module-name migrated-query-policy-positive-fixture \
    -no-stdlib -no-reflect \
    -classpath "$FIXTURE_CLASSPATH" \
    -d "$TEMP_DIR/classes/kotlin-migrated-query-policy" \
    "$TEMP_DIR/kotlin/MigratedQueryPolicy.kt"
echo "PASS: Migrated QueryPolicy external fixtures compile"
