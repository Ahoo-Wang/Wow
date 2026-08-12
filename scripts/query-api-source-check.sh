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

[[ $# -eq 3 ]] || {
    echo "Usage: $0 WOW_QUERY_JAR RUNTIME_CLASSPATH KOTLIN_COMPILER_CLASSPATH" >&2
    exit 64
}

readonly WOW_QUERY_JAR="$1"
readonly RUNTIME_CLASSPATH="$2"
readonly KOTLIN_COMPILER_CLASSPATH="$3"
readonly FIXTURE_CLASSPATH="$WOW_QUERY_JAR:$RUNTIME_CLASSPATH"
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
[[ -n "$RUNTIME_CLASSPATH" ]] || fail "Runtime classpath is empty"
[[ -n "$KOTLIN_COMPILER_CLASSPATH" ]] || fail "Kotlin compiler classpath is empty"

IFS=':' read -r -a runtime_entries <<<"$RUNTIME_CLASSPATH"
for runtime_entry in "${runtime_entries[@]}"; do
    [[ -f "$runtime_entry" && "$runtime_entry" == *.jar ]] ||
        fail "Runtime classpath must contain dependency JARs only: $runtime_entry"
    [[ "$runtime_entry" != "$WOW_QUERY_JAR" ]] ||
        fail "Published wow-query JAR must not be duplicated on the runtime dependency classpath"
done

mkdir -p "$TEMP_DIR/java" "$TEMP_DIR/kotlin" "$TEMP_DIR/classes/java" "$TEMP_DIR/classes/kotlin"

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
import reactor.core.publisher.Mono;

public final class StableAdmissionSpi {
    public static QueryPolicy policy() {
        return context -> Mono.just(new QueryPolicyResult());
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
import me.ahoo.wow.api.query.expression.QueryExpression;
import me.ahoo.wow.api.query.gateway.QueryPage;
import me.ahoo.wow.api.query.gateway.QuerySort;
import me.ahoo.wow.api.query.gateway.QueryTarget;
import me.ahoo.wow.query.backend.QueryBackend;
import me.ahoo.wow.query.backend.QueryBackendDescriptor;
import me.ahoo.wow.query.backend.QueryBackendReadiness;
import me.ahoo.wow.query.backend.QueryBackendResolver;
import me.ahoo.wow.query.backend.QueryBackendRouteIdentity;
import me.ahoo.wow.query.backend.QueryPlanVersion;
import me.ahoo.wow.query.invocation.QueryProvenance;
import me.ahoo.wow.query.plan.CountQueryPlanV1;
import me.ahoo.wow.query.plan.ListQueryPlanV1;
import me.ahoo.wow.query.plan.PageQueryPlanV1;
import me.ahoo.wow.query.plan.QueryPlanResultShape;
import me.ahoo.wow.query.plan.QueryPlanV1;
import me.ahoo.wow.query.plan.SingleQueryPlanV1;
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

    public static QueryBackendResolver resolver() {
        return target -> Mono.empty();
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
import reactor.core.publisher.Mono

val stablePolicy = QueryPolicy { Mono.just(QueryPolicyResult()) }

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
import me.ahoo.wow.query.backend.QueryBackendReadiness
import me.ahoo.wow.query.backend.QueryBackendResolver
import me.ahoo.wow.query.plan.CountQueryPlanV1
import me.ahoo.wow.query.plan.ListQueryPlanV1
import me.ahoo.wow.query.plan.PageQueryPlanV1
import me.ahoo.wow.query.plan.QueryPlanV1
import me.ahoo.wow.query.plan.SingleQueryPlanV1
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

class StableBackendSpi(override val descriptor: QueryBackendDescriptor) : QueryBackend {
    override fun <R : Any> single(plan: SingleQueryPlanV1<R>): Mono<R> = Mono.empty()
    override fun <R : Any> list(plan: ListQueryPlanV1<R>): Flux<R> = Flux.empty()
    override fun <R : Any> page(plan: PageQueryPlanV1<R>): Mono<QueryPage<R>> = Mono.empty()
    override fun count(plan: CountQueryPlanV1): Mono<Long> = Mono.just(0)
    override fun readiness(): Mono<QueryBackendReadiness> = Mono.just(QueryBackendReadiness.Ready)
}

val stableResolver = QueryBackendResolver { Mono.empty() }

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

cat >"$TEMP_DIR/kotlin/InternalAdmissionImplementations.kt" <<'EOF'
package external.fixture

import me.ahoo.wow.query.invocation.DefaultQueryAdmission
import me.ahoo.wow.query.invocation.QueryDeadline
import me.ahoo.wow.query.invocation.QueryDeadlineExceededException
import me.ahoo.wow.query.invocation.QueryDeadlineGuard
import me.ahoo.wow.query.invocation.QueryInvocation
import me.ahoo.wow.query.invocation.QueryInvocationFactory
import me.ahoo.wow.query.invocation.QueryInvocationSeed
import me.ahoo.wow.query.policy.CombinedQueryPolicyResult
import me.ahoo.wow.query.policy.DefaultQueryPolicyChain
import me.ahoo.wow.query.policy.QueryPolicyDescriptor
import me.ahoo.wow.query.policy.SystemQueryPolicy
import me.ahoo.wow.query.schema.QuerySchemaView
import me.ahoo.wow.query.schema.immutableSnapshot

fun internalImplementations(): List<Class<*>> = listOf(
    DefaultQueryAdmission::class.java,
    QueryDeadline::class.java,
    QueryDeadlineExceededException::class.java,
    QueryDeadlineGuard::class.java,
    QueryInvocation::class.java,
    QueryInvocationFactory::class.java,
    QueryInvocationSeed::class.java,
    CombinedQueryPolicyResult::class.java,
    DefaultQueryPolicyChain::class.java,
    QueryPolicyDescriptor::class.java,
    SystemQueryPolicy::class.java
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

for class_name in DefaultQueryAdmission QueryDeadline QueryDeadlineExceededException QueryDeadlineGuard \
    QueryInvocation QueryInvocationFactory QueryInvocationSeed \
    CombinedQueryPolicyResult DefaultQueryPolicyChain QueryPolicyDescriptor SystemQueryPolicy; do
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
