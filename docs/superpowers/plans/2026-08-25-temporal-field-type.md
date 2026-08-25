# 类型化逻辑字段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `LogicalField(name, type?)` 以字符串/对象双形态承载通用 `FieldType -> Temporal` 骨架，并让 RelativeTimeFilter、MongoDB/Elasticsearch DateHistogram 按显式 Date/Number/String 合同执行。

**Architecture:** `wow-api` 集中定义 FieldType、LogicalField 双形态 Jackson 边界和时间默认解析；时间操作只读取 `LogicalField.type`，不再持有 sibling fieldType。`wow-query` 用内部 RelativeTimeFilterNormalizer 展开时间范围；MongoDB 与 Elasticsearch 分别编译 Date/Number，Elasticsearch Number 使用请求级 date runtime field。

**Tech Stack:** Kotlin 2.4.10、JVM 17、Jackson 3.1、Swagger/OpenAPI 3.1、MongoDB aggregation、Elasticsearch Java Client/Painless、JUnit Jupiter、MockK、Reactor Test、Testcontainers、VitePress。

**Spec:** `docs/superpowers/specs/2026-08-25-temporal-field-type-design.md`

## Global Constraints

- `FieldType` 当前只有子接口 `Temporal`；Temporal 叶子 JSON subtype 仅为 DATE、TEMPORAL_NUMBER、TEMPORAL_STRING。Kotlin 类型名保持 Number、String。
- 不读取旧 NUMBER、STRING subtype alias；近期查询协议不要求该 JSON 兼容。
- 不增加 AUTO/INFER 或额外 TEMPORAL JSON 包装层。
- `LogicalField` 公共属性固定为 `name: String`、`type: FieldType?`；`value` 全面重命名为 `name`。
- LogicalField JSON：无类型序列化为字符串；带类型序列化为 `{name,type}`；读取同时接受字符串和对象，对象可省略 type，但拒绝显式 `type:null` 和未知属性。
- 时间操作遇到 `type == null` 时默认 `FieldType.Temporal.Number(MILLISECONDS)`。
- RelativeTimeFilter 允许任意 Temporal；DateHistogram 拒绝 String；未来非 Temporal FieldType 由时间操作拒绝。
- DateHistogram.timeZone 默认 `ZoneId.systemDefault()`；跨后端测试显式使用 UTC 或 Asia/Shanghai。
- `AggregationDateUnit` 不改；`TimeUnit` 只描述 Number 存储单位。
- Condition 协议不改；String pattern 与任意 runtime DateTimeFormatter 必须进入 LogicalField.type 并保持语义。
- Number 只接受恰好一个数值（标量或 singleton 数组）、有限、可无损为 Long 的整数；空数组、多值数组和其他无效值不产生桶。
- Elasticsearch 脚本字段名只通过 params 传入；乘法使用显式有符号 Long multiplier 边界检查，越界不 emit，不依赖异常控制流。
- 不改默认 Elasticsearch 模板，不迁移索引，不增加依赖、模块、配置、Catalog、Scanner 或持久化 runtime field。
- 测试使用 FluentAssert `.assert()`；行为变更严格执行 RED → GREEN。
- 每次提交只 stage 明确文件，保留用户无关修改。

---

## File Map

### 新建

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FieldType.kt`
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/LogicalFieldJsonSerializer.kt`
- `wow-api/src/test/kotlin/me/ahoo/wow/api/query/LogicalFieldTest.kt`
- `wow-query/src/main/kotlin/me/ahoo/wow/query/RelativeTimeFilterNormalizer.kt`
- `wow-query/src/test/kotlin/me/ahoo/wow/query/RelativeTimeFilterNormalizerTest.kt`

### 主要修改

- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt`
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/RelativeTimeFilters.kt`
- `wow-api/src/main/kotlin/me/ahoo/wow/api/query/LegacyConditionAdapter.kt`
- `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt`
- `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt`
- `wow-query/src/main/kotlin/me/ahoo/wow/query/FilterNormalizer.kt`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterConverter.kt`
- `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt`
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchFilterConverter.kt`
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt`
- `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt`
- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt`
- `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt`
- `schema/query/v2/filter-expression.schema.json`、wow-schema/OpenAPI snapshots、两个 query guide

---

### Task 1: FieldType 骨架与 LogicalField 双形态

**Files:**
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FieldType.kt`
- Create: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/LogicalFieldJsonSerializer.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt:23-40`
- Create: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/LogicalFieldTest.kt`
- Modify: LogicalField name accesses found by `rg -n "(field|path)\\.value|\\.field\\.value|\\.path\\.value" --glob '*.kt'`

**Interfaces:**
- Produces: `FieldType`, `FieldType.Temporal`, `LogicalField(name,type?)`, `LogicalField.temporalTypeOrDefault()`.
- JSON produces string for untyped and object `{name,type}` for typed fields.

- [ ] **Step 1: Write failing FieldType and LogicalField tests**

```kotlin
class LogicalFieldTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `untyped logical field should use string JSON`() {
        val field = LogicalField("state.createdAt")

        mapper.writeValueAsString(field).assert().isEqualTo("\"state.createdAt\"")
        mapper.readValue("\"state.createdAt\"", LogicalField::class.java).assert().isEqualTo(field)
    }

    @Test
    fun `typed logical field should use name and type object JSON`() {
        val field = LogicalField(
            "snapshotTime",
            FieldType.Temporal.Number(TimeUnit.MILLISECONDS),
        )

        val json = mapper.writeValueAsString(field)
        json.assert()
            .contains("\"name\":\"snapshotTime\"")
            .contains("\"type\":{\"type\":\"TEMPORAL_NUMBER\",\"timeUnit\":\"MILLISECONDS\"}")
        mapper.readValue(json, LogicalField::class.java).assert().isEqualTo(field)
    }

    @Test
    fun `logical field should reject invalid JSON shapes`() {
        assertThrows<JacksonException> {
            mapper.readValue("1", LogicalField::class.java)
        }
        assertThrows<JacksonException> {
            mapper.readValue("""{"type":{"type":"DATE"}}""", LogicalField::class.java)
        }
    }
}
```

Add FieldType round-trip and formatter XOR tests:

```kotlin
val values = listOf<FieldType>(
    FieldType.Temporal.Date,
    FieldType.Temporal.Number(TimeUnit.SECONDS),
    FieldType.Temporal.String(datePattern = "yyyy-MM-dd"),
)
values.forEach { value ->
    val json = mapper.writeValueAsString(value)
    mapper.readValue(json, FieldType::class.java).assert().isEqualTo(value)
}

assertThrows<IllegalArgumentException> { FieldType.Temporal.String() }
assertThrows<IllegalArgumentException> {
    FieldType.Temporal.String(
        datePattern = "yyyy-MM-dd",
        dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE,
    )
}
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.LogicalFieldTest" --stacktrace
```

Expected: unresolved FieldType and LogicalField still has value/@JsonValue only.

- [ ] **Step 3: Implement FieldType**

Create the sealed skeleton with Jackson registration mirroring the type hierarchy:

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(FieldType.Temporal::class),
)
@Schema(
    oneOf = [FieldType.Temporal::class],
)
sealed interface FieldType {
    @JsonSubTypes(
        JsonSubTypes.Type(Temporal.Date::class, name = "DATE"),
        JsonSubTypes.Type(Temporal.Number::class, name = "TEMPORAL_NUMBER"),
        JsonSubTypes.Type(Temporal.String::class, name = "TEMPORAL_STRING"),
    )
    @Schema(
        oneOf = [
            FieldType.Temporal.Date::class,
            FieldType.Temporal.Number::class,
            FieldType.Temporal.String::class,
        ],
        discriminatorProperty = "type",
    )
    sealed interface Temporal : FieldType {
        @JsonTypeName("DATE")
        data object Date : Temporal

        @JsonTypeName("TEMPORAL_NUMBER")
        data class Number(
            val timeUnit: TimeUnit = TimeUnit.MILLISECONDS,
        ) : Temporal

        @JsonTypeName("TEMPORAL_STRING")
        data class String(
            @get:Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            val datePattern: String? = null,
            @get:JsonIgnore
            @get:Schema(hidden = true)
            val dateFormatter: DateTimeFormatter? = null,
        ) : Temporal {
            init {
                require((datePattern == null) != (dateFormatter == null)) {
                    "String requires exactly one of datePattern or dateFormatter."
                }
                datePattern?.let {
                    require(it.isNotBlank()) { "datePattern cannot be blank." }
                    DateTimeFormatter.ofPattern(it)
                }
            }

            @get:JsonIgnore
            val formatter: DateTimeFormatter
                get() = dateFormatter ?: DateTimeFormatter.ofPattern(requireNotNull(datePattern))
        }
    }
}
```

- [ ] **Step 4: Implement centralized LogicalField serialization**

Change LogicalField:

```kotlin
@JsonSerialize(using = LogicalFieldJsonSerializer::class)
@JsonDeserialize(using = LogicalFieldJsonDeserializer::class)
@Schema(types = ["string", "object"])
data class LogicalField(
    val name: String,
    val type: FieldType? = null,
) {
    init {
        require(LOGICAL_FIELD_PATTERN.matches(name)) { "Logical field is invalid: [$name]." }
    }

    override fun toString(): String = name

    fun temporalTypeOrDefault(): FieldType.Temporal = when (val declared = type) {
        null -> FieldType.Temporal.Number()
        is FieldType.Temporal -> declared
        else -> throw IllegalArgumentException(
            "Logical field [$name] type [${declared::class.java.name}] is not temporal.",
        )
    }
}
```

Serializer:

```kotlin
object LogicalFieldJsonSerializer : StdSerializer<LogicalField>(LogicalField::class.java) {
    override fun serialize(
        value: LogicalField,
        generator: JsonGenerator,
        provider: SerializationContext,
    ) {
        if (value.type == null) {
            generator.writeString(value.name)
            return
        }
        generator.writeStartObject()
        generator.writeStringProperty("name", value.name)
        generator.writePOJOProperty("type", value.type)
        generator.writeEndObject()
    }
}
```

Deserializer:

```kotlin
object LogicalFieldJsonDeserializer : StdDeserializer<LogicalField>(LogicalField::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): LogicalField {
        val node = parser.objectReadContext().readTree<JsonNode>(parser)
        if (node.isString) return LogicalField(node.asString())
        if (!node.isObject) {
            return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField must be a string or object.",
            )
        }
        val unknownProperties = node.properties().map { it.key }
            .filterNot { it == "name" || it == "type" }
        if (unknownProperties.isNotEmpty()) {
            return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField object contains unknown properties $unknownProperties.",
            )
        }
        val name = node["name"]?.takeIf(JsonNode::isString)?.asString()
            ?: return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField object requires string property [name].",
            )
        val typeNode = node["type"]
        if (typeNode?.isNull == true) {
            return context.reportInputMismatch(
                LogicalField::class.java,
                "LogicalField object property [type] cannot be null.",
            )
        }
        val type = typeNode?.let { context.readTreeAsValue(it, FieldType::class.java) }
        return LogicalField(name, type)
    }
}
```

- [ ] **Step 5: Rename LogicalField.value to name everywhere**

Use `rg -n "\\.value" wow-api/src/main/kotlin/me/ahoo/wow/api/query wow-query/src wow-mongo/src wow-elasticsearch/src test/wow-tck/src`. Change only accesses whose receiver is LogicalField. Preserve JsonNode/FieldValue/message `.value` calls.

Key replacements:

```kotlin
field.value -> field.name
element.path.value -> element.path.name
LogicalField.from(value) -> LogicalField(value)
```

When resolving a physical name, preserve type:

```kotlin
field.copy(name = resolvedName)
```

Do not recreate `LogicalField(resolvedName)` when an existing typed field is being resolved.

- [ ] **Step 6: Run module compilation and GREEN tests**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.LogicalFieldTest" \
  :wow-api:compileKotlin :wow-query:compileKotlin \
  :wow-mongo:compileKotlin :wow-elasticsearch:compileKotlin --stacktrace
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/FieldType.kt \
  wow-api/src/main/kotlin/me/ahoo/wow/api/query/LogicalFieldJsonSerializer.kt \
  wow-api/src/main/kotlin/me/ahoo/wow/api/query/FilterExpression.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/LogicalFieldTest.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterConverter.kt \
  wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchFilterConverter.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/FilterExpressionTest.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt
git commit -m "refactor(query): support typed logical fields"
```

Before committing, inspect `git diff --name-only --cached`; every staged file must be listed above and contain only the name→type migration.

---

### Task 2: DateHistogram 从 LogicalField 读取时间类型

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt:117-172`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt:54-70`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt`
- Migrate: DateHistogram Kotlin call sites in Mongo/Elasticsearch/TCK tests.

**Interfaces:**
- Consumes: LogicalField.temporalTypeOrDefault().
- Produces: DateHistogram accepts untyped/DATE/Number fields, rejects String/non-Temporal, and defaults system time zone.

- [ ] **Step 1: Write failing DateHistogram tests**

```kotlin
@Test
fun `date histogram should use logical field temporal type`() {
    val number = AggregationGroup.DateHistogram(
        field = LogicalField("snapshotTime"),
        alias = "day",
        unit = AggregationDateUnit.DAY,
    )
    number.field.temporalTypeOrDefault().assert()
        .isEqualTo(FieldType.Temporal.Number())
    number.timeZone.assert().isEqualTo(ZoneId.systemDefault().id)

    AggregationGroup.DateHistogram(
        field = LogicalField("createdAt", FieldType.Temporal.Date),
        alias = "day",
        unit = AggregationDateUnit.DAY,
    )
}

@Test
fun `date histogram should reject String`() {
    assertThrows<IllegalArgumentException> {
        AggregationGroup.DateHistogram(
            field = LogicalField(
                "createdAt",
                FieldType.Temporal.String(datePattern = "yyyy-MM-dd"),
            ),
            alias = "day",
            unit = AggregationDateUnit.DAY,
        )
    }
}
```

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest" --stacktrace
```

Expected: DateHistogram does not validate field.type and still defaults UTC.

- [ ] **Step 3: Implement model validation and DSL overloads**

```kotlin
data class DateHistogram(
    override val field: LogicalField,
    override val alias: String,
    val unit: AggregationDateUnit,
    val timeZone: String = ZoneId.systemDefault().id,
) : AggregationGroup {
    init {
        requireAggregationAlias(alias)
        require(field.temporalTypeOrDefault() !is FieldType.Temporal.String) {
            "DateHistogram does not support TEMPORAL_STRING fields."
        }
        ZoneId.of(timeZone)
    }
}
```

DSL:

```kotlin
fun dateHistogram(
    field: LogicalField,
    unit: AggregationDateUnit,
    alias: String,
    timeZone: ZoneId = ZoneId.systemDefault(),
) {
    groups += AggregationGroup.DateHistogram(field, alias, unit, timeZone.id)
}

fun dateHistogram(
    field: String,
    unit: AggregationDateUnit,
    alias: String,
    timeZone: ZoneId = ZoneId.systemDefault(),
) = dateHistogram(LogicalField(field), unit, alias, timeZone)
```

- [ ] **Step 4: Migrate existing native-date call sites**

Use typed LogicalField:

```kotlin
dateHistogram(
    field = LogicalField("state.createdAt", FieldType.Temporal.Date),
    unit = AggregationDateUnit.DAY,
    alias = "day",
    timeZone = ZoneId.of("Asia/Shanghai"),
)
```

Keep snapshotTime strings untyped only where default Number(MILLISECONDS) is intended.

- [ ] **Step 5: Run GREEN and commit**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.AggregationQueryTest" \
  :wow-query:test --tests "me.ahoo.wow.query.dsl.AggregationQueryDslTest" --stacktrace

git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/AggregationQueryTest.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDsl.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/AggregationQueryDslTest.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt \
  test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt
git commit -m "feat(query): declare temporal histogram fields"
```

---

### Task 3: RelativeTimeFilter 与独立归一化器

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/RelativeTimeFilters.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/LegacyConditionAdapter.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt`
- Create: `wow-query/src/main/kotlin/me/ahoo/wow/query/RelativeTimeFilterNormalizer.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/FilterNormalizer.kt`
- Test: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/FilterExpressionTest.kt`
- Test: `wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/FilterDslTest.kt`
- Create: `wow-query/src/test/kotlin/me/ahoo/wow/query/RelativeTimeFilterNormalizerTest.kt`
- Modify: `wow-query/src/test/kotlin/me/ahoo/wow/query/FilterNormalizerTest.kt`

**Interfaces:**
- RelativeTimeFilter only exposes field/zoneId.
- DSL accepts optional `type: FieldType.Temporal?`.
- Normalizer reads `field.temporalTypeOrDefault()`.

- [ ] **Step 1: Write failing model/Condition tests**

```kotlin
val filters = listOf<RelativeTimeFilter>(
    TodayFilter(LogicalField("nativeDate", FieldType.Temporal.Date)),
    TodayFilter(
        LogicalField(
            "epochSeconds",
            FieldType.Temporal.Number(TimeUnit.SECONDS),
        ),
    ),
    TodayFilter(
        LogicalField(
            "dateText",
            FieldType.Temporal.String(datePattern = "yyyy-MM-dd"),
        ),
    ),
)
filters.forEach { filter ->
    val json = jsonMapper.writeValueAsString(filter)
    jsonMapper.readValue(json, FilterExpression::class.java).assert().isEqualTo(filter)
}
```

Condition runtime formatter:

```kotlin
val formatter = DateTimeFormatter.ISO_LOCAL_DATE
Condition.today("createdAt", formatter).toFilterExpression().assert().isEqualTo(
    TodayFilter(
        LogicalField(
            "createdAt",
            FieldType.Temporal.String(dateFormatter = formatter),
        ),
    ),
)
```

- [ ] **Step 2: Write failing normalizer tests**

```kotlin
private val now = Instant.parse("2026-08-22T12:00:00Z")
private val normalizer = RelativeTimeFilterNormalizer(ZoneOffset.UTC)

@Test
fun `DATE should create millisecond Instant boundaries`() {
    val normalized = normalizer.normalize(
        TodayFilter(
            LogicalField("createdAt", FieldType.Temporal.Date),
            zoneId = "UTC",
        ),
        now,
    ) as AndFilter

    ((normalized.operands[0] as GreaterThanOrEqualFilter).value as POJONode).pojo.assert()
        .isEqualTo(Instant.parse("2026-08-22T00:00:00Z"))
}
```

Add Number(SECONDS) and String cases. Move existing calendar/DST/leap-year/Monday/nanosecond/nested tests from FilterNormalizerTest without changing expected values.

- [ ] **Step 3: Run RED**

```bash
./gradlew :wow-api:test --tests "me.ahoo.wow.api.query.FilterExpressionTest" \
  :wow-query:test --tests "me.ahoo.wow.query.RelativeTimeFilterNormalizerTest" \
  :wow-query:test --tests "me.ahoo.wow.query.dsl.FilterDslTest" --stacktrace
```

Expected: flat RelativeTime properties remain and the new normalizer is absent.

- [ ] **Step 4: Refactor fifteen RelativeTime classes**

Interface:

```kotlin
sealed interface RelativeTimeFilter : FilterExpression {
    val field: LogicalField
    val zoneId: String?
}
```

Constructor categories:

```kotlin
data class TodayFilter(
    override val field: LogicalField,
    override val zoneId: String? = null,
) : RelativeTimeFilter

data class BeforeTodayFilter(
    override val field: LogicalField,
    val time: String,
    override val zoneId: String? = null,
) : RelativeTimeFilter

data class RecentDaysFilter(
    override val field: LogicalField,
    val days: Int,
    override val zoneId: String? = null,
) : RelativeTimeFilter
```

Use the first for TODAY/YESTERDAY/TOMORROW/week/month/year variants, second for BEFORE_TODAY, third for RECENT_DAYS/EARLIER_DAYS. Delete flat datePattern/dateFormatter/timeUnit.

Keep one shared configuration validator and make it call `field.temporalTypeOrDefault()` plus existing zone validation, so future non-Temporal FieldType values fail when a RelativeTimeFilter is constructed.

- [ ] **Step 5: Preserve Condition conversion**

```kotlin
private val Condition.logicalField: LogicalField
    get() {
        val type = when (val format = options[Condition.DATE_PATTERN_OPTION_KEY]) {
            null -> null
            is String -> FieldType.Temporal.String(datePattern = format)
            is DateTimeFormatter -> FieldType.Temporal.String(dateFormatter = format)
            else -> error("Unsupported datePattern option: ${format::class.java.name}.")
        }
        return LogicalField(field, type)
    }
```

Pass this field and zoneValue to every legacy relative constructor. Do not modify Condition.kt.

- [ ] **Step 6: Replace Filter DSL time signatures**

```kotlin
fun String.today(
    type: FieldType.Temporal? = null,
    zoneId: ZoneId? = null,
) = add(TodayFilter(LogicalField(field(this).name, type), zoneId?.id))

fun String.beforeToday(
    time: LocalTime,
    type: FieldType.Temporal? = null,
    zoneId: ZoneId? = null,
) = add(BeforeTodayFilter(LogicalField(field(this).name, type), time.toString(), zoneId?.id))
```

Apply the same optional type/zone pattern to all relative methods. Do not retain old overloads.

- [ ] **Step 7: Extract normalizer**

Use an internal class with `normalize(expression, now)`. Boundary selection:

```kotlin
return when (val type = field.temporalTypeOrDefault()) {
    FieldType.Temporal.Date -> JsonNodeFactory.instance.pojoNode(
        Instant.ofEpochMilli(instant.toEpochMilli()),
    )
    is FieldType.Temporal.Number -> JsonNodeFactory.instance.numberNode(
        Math.addExact(
            type.timeUnit.convert(instant.epochSecond, TimeUnit.SECONDS),
            type.timeUnit.convert(instant.nano.toLong(), TimeUnit.NANOSECONDS),
        ),
    )
    is FieldType.Temporal.String ->
        JsonNodeFactory.instance.stringNode(type.formatter.format(dateTime.atZone(zoneId)))
}
```

FilterNormalizer captures `clock.instant()` once and delegates, then simplifies.

- [ ] **Step 8: Run GREEN and commit**

```bash
./gradlew :wow-api:test :wow-query:test --stacktrace

git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/RelativeTimeFilters.kt \
  wow-api/src/main/kotlin/me/ahoo/wow/api/query/LegacyConditionAdapter.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/FilterExpressionTest.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/FilterNormalizer.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/RelativeTimeFilterNormalizer.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/FilterNormalizerTest.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/RelativeTimeFilterNormalizerTest.kt \
  wow-query/src/test/kotlin/me/ahoo/wow/query/dsl/FilterDslTest.kt
git commit -m "refactor(query): normalize typed relative time fields"
```
---

### Task 4: DATE 范围适配与 Elasticsearch typed mapping

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterConverter.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchFilterConverter.kt`
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/SnapshotFilterConverterTest.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchFilterConverterTest.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt`

**Interfaces:**
- Consumes: POJONode Instant and typed LogicalField.
- Produces: backend-native DATE boundary and `resolveTemporal(field, docValuesRequired): LogicalField`.

- [ ] **Step 1: Write failing converter tests**

Mongo:

```kotlin
val instant = Instant.parse("2026-08-22T00:00:00Z")
val filter = GreaterThanOrEqualFilter(
    LogicalField("state.createdAt", FieldType.Temporal.Date),
    JsonNodeFactory.instance.pojoNode(instant),
)
SnapshotFilterConverter.convert(filter).toBsonDocument().assert().isEqualTo(
    Filters.gte("state.createdAt", Date.from(instant)).toBsonDocument(),
)
```

Elasticsearch renders the same Instant as `1787356800000`.

- [ ] **Step 2: Write failing mapping tests**

Create date, date_nanos, long, double, keyword and long/docValues(false) mappings:

```kotlin
mapping.resolveTemporal(
    LogicalField("state.date", FieldType.Temporal.Date),
    docValuesRequired = true,
).assert().isEqualTo(LogicalField("state.date", FieldType.Temporal.Date))

mapping.resolveTemporal(
    LogicalField(
        "state.epoch",
        FieldType.Temporal.Number(TimeUnit.SECONDS),
    ),
    docValuesRequired = true,
).type.assert().isEqualTo(FieldType.Temporal.Number(TimeUnit.SECONDS))
```

Assert DATE-on-long, Number-on-date, String-on-long and Number without doc values fail with clear field/declared/actual/expected messages.

- [ ] **Step 3: Run RED**

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.SnapshotFilterConverterTest" \
  :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.ElasticsearchFilterConverterTest" \
  :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolverTest" \
  --stacktrace
```

Expected: Instant is raw and resolveTemporal is absent.

- [ ] **Step 4: Adapt Instant only in existing nativeValue functions**

Mongo:

```kotlin
isPojo -> when (val value = (this as POJONode).pojo) {
    is Instant -> Date.from(value)
    else -> value
}
```

Elasticsearch:

```kotlin
isPojo -> when (val value = (this as POJONode).pojo) {
    is Instant -> value.toEpochMilli()
    else -> value
}
```

- [ ] **Step 5: Implement typed mapping resolution**

```kotlin
internal fun resolveTemporal(
    field: LogicalField,
    docValuesRequired: Boolean,
): LogicalField
```

Resolve `field.temporalTypeOrDefault()` against mapping kinds: DATE→date/date_nanos, Number→numeric, String→keyword-family range fields. Require existing sortable/doc-values capability only when requested. Return `field.copy(name = resolvedName)` so type survives multi-field resolution.

Update all regular LogicalField resolver helpers to preserve type when names change.

- [ ] **Step 6: Run GREEN and commit**

```bash
./gradlew :wow-mongo:test --tests "me.ahoo.wow.mongo.query.SnapshotFilterConverterTest" \
  :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.ElasticsearchFilterConverterTest" \
  :wow-elasticsearch:test --tests "me.ahoo.wow.elasticsearch.query.ElasticsearchIndexMappingResolverTest" \
  --stacktrace

git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/AbstractMongoFilterConverter.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/SnapshotFilterConverterTest.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/AbstractElasticsearchFilterConverter.kt \
  wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolver.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchFilterConverterTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/ElasticsearchIndexMappingResolverTest.kt
git commit -m "feat(query): resolve typed temporal mappings"
```

---

### Task 5: MongoDB DATE/Number DateHistogram

**Files:**
- Modify: `wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt`
- Test: `wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt`
- Test: `wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt`

**Interfaces:**
- Reads `group.field.temporalTypeOrDefault()`.
- DATE uses native BSON Date; Number uses guarded internal Date.

- [ ] **Step 1: Write failing compiler tests**

```kotlin
val datePipeline = MongoAggregationCompiler(SnapshotFilterConverter).compile(
    aggregation {
        dateHistogram(
            LogicalField("state.createdAt", FieldType.Temporal.Date),
            AggregationDateUnit.DAY,
            "day",
            ZoneOffset.UTC,
        )
        count("count")
    },
).joinToString { it.toBsonDocument().toJson() }
datePipeline.assert().contains("\$dateTrunc").doesNotContain("\$toDate")
```

Number/SECONDS:

```kotlin
val numberPipeline = MongoAggregationCompiler(SnapshotFilterConverter).compile(
    aggregation {
        dateHistogram(
            LogicalField(
                "state.epochSecond",
                FieldType.Temporal.Number(TimeUnit.SECONDS),
            ),
            AggregationDateUnit.DAY,
            "day",
            ZoneOffset.UTC,
        )
        count("count")
    },
).joinToString { it.toBsonDocument().toJson() }

numberPipeline.assert()
    .doesNotContain("\$set")
    .contains("\$isNumber")
    .contains("\$convert")
    .contains("\$multiply")
```

Add nanos/micros division and millis/minutes/hours/days multiplication factor assertions.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-mongo:test --tests \
  "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest" --stacktrace
```

Expected: DATE still uses `$toDate`; Number lacks normalization.

- [ ] **Step 3: Inline the Number date expression**

Collect DateHistogram aliases whose resolved type is Number so null group keys can be removed after grouping:

```kotlin
val numericDateAliases = query.groupBy.filterIsInstance<AggregationGroup.DateHistogram>()
    .filter { it.field.temporalTypeOrDefault() is FieldType.Temporal.Number }
    .map { it.alias }
```

`numericDate` must use a scalar directly or unwrap an array only when its size is exactly one, then apply `$isNumber`, `$convert` raw→Long, raw/integer equality, Decimal128 multiply/divide, final Long conversion and Date conversion. Set `onError: null` and `onNull: null` on conversions. Inline the result into `$dateTrunc`, then exclude `_id.<alias> == null` groups after `$group`; do not create scratch fields in the source document.

- [ ] **Step 4: Split group expression**

```kotlin
val dateInput = when (field.temporalTypeOrDefault()) {
    FieldType.Temporal.Date -> "\$${field.resolve(parent)}"
    is FieldType.Temporal.Number -> numericDate(parent)
    is FieldType.Temporal.String ->
        error("DateHistogram does not support TEMPORAL_STRING fields.")
}
```

Preserve timezone, SECOND, `startOfWeek: Monday` and final `$toLong`.

- [ ] **Step 5: Run compiler GREEN**

```bash
./gradlew :wow-mongo:test --tests \
  "me.ahoo.wow.mongo.query.snapshot.MongoAggregationCompilerTest" --stacktrace
```

Expected: PASS.

- [ ] **Step 6: Add real invalid-value integration**

Insert scalar, singleton array, missing, null, empty array, multi array, string, `1.5`, and `Long.MAX_VALUE` raw documents:

```kotlin
Document("_id", "valid")
    .append("deleted", false)
    .append("state", Document("epoch", 1_767_225_600_000L))
Document("_id", "singleton")
    .append("deleted", false)
    .append("state", Document("epoch", listOf(1_767_225_600_000L)))
Document("_id", "multi")
    .append("deleted", false)
    .append("state", Document("epoch", listOf(1_767_225_600_000L, 1_767_312_000_000L)))
Document("_id", "overflow")
    .append("deleted", false)
    .append("state", Document("epoch", Long.MAX_VALUE))
```

Run Number(MILLISECONDS) and Number(DAYS); only a numeric scalar or singleton numeric array may produce buckets; empty and multi arrays remain invalid.

```bash
./gradlew :wow-mongo:integrationTest --tests \
  "me.ahoo.wow.mongo.query.snapshot.MongoSnapshotQueryServiceTest" --stacktrace
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add wow-mongo/src/main/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompiler.kt \
  wow-mongo/src/test/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoAggregationCompilerTest.kt \
  wow-mongo/src/integrationTest/kotlin/me/ahoo/wow/mongo/query/snapshot/MongoSnapshotQueryServiceTest.kt
git commit -m "feat(mongo): compile typed temporal histograms"
```

---

### Task 6: Elasticsearch Number runtime date 与 pagination

**Files:**
- Modify: `wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt`
- Test: `wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt`

**Interfaces:**
- DATE resolves typed field with doc values.
- Number creates request RuntimeFieldType.Date and composite source.

- [ ] **Step 1: Write failing compiler tests**

```kotlin
val plan = ElasticsearchAggregationCompiler(SnapshotFilterConverter, mapping).compile(
    aggregation {
        dateHistogram(
            LogicalField(
                "snapshotTime",
                FieldType.Temporal.Number(TimeUnit.SECONDS),
            ),
            AggregationDateUnit.DAY,
            "day",
            ZoneOffset.UTC,
        )
        count("count")
    },
)

val runtime = plan.runtimeMappings.getValue("__wow_date_histogram_0")
runtime.type().assert().isEqualTo(RuntimeFieldType.Date)
runtime.script()!!.source()!!.scriptString().assert()
    .contains("value<=Long.MAX_VALUE/multiplier")
    .contains("value>=Long.MIN_VALUE/multiplier")
    .doesNotContain("snapshotTime")
    .doesNotContain("catch(")
runtime.script()!!.params().getValue("field").to(String::class.java).assert()
    .isEqualTo("snapshotTime")
```

DATE/date_nanos must use native field and leave runtimeMappings empty. Add all TimeUnit factor cases.

- [ ] **Step 2: Run RED**

```bash
./gradlew :wow-elasticsearch:test --tests \
  "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest" --stacktrace
```

Expected: Number still targets numeric field directly.

- [ ] **Step 3: Build runtime map before group sources**

Preserve original group index:

```kotlin
val indexedGroups = query.groupBy.withIndex().associateBy { it.value.alias }
val runtimeMappings = linkedMapOf<String, RuntimeField>()
val groupSources = effectiveSort.mapNotNull { sort ->
    indexedGroups[sort.field]?.let { indexed ->
        indexed.value.toSource(parent, sort, indexed.index, runtimeMappings)
    }
}
```

- [ ] **Step 4: Implement Number runtime date**

Resolve typed LogicalField with `docValuesRequired = true`. DATE uses resolved `name`. Number creates `__wow_date_histogram_<index>` and passes `field`, `multiplier`, `divisor` as JsonData params.

Painless source:

```text
String field=params.field;
if(doc.containsKey(field)&&doc[field].size()==1){
  def raw=doc[field].value;
  if(raw instanceof Number){
    boolean integral=raw instanceof Byte||raw instanceof Short||raw instanceof Integer||raw instanceof Long;
    boolean floating=raw instanceof Float||raw instanceof Double;
    double candidate=((Number)raw).doubleValue();
    if(integral||(floating&&Double.isFinite(candidate)&&candidate==Math.rint(candidate)&&candidate>=Long.MIN_VALUE&&candidate<Long.MAX_VALUE)){
      long value=((Number)raw).longValue();
      long multiplier=((Number)params.multiplier).longValue();
      long divisor=((Number)params.divisor).longValue();
      if(divisor==1L){
        if(value<=Long.MAX_VALUE/multiplier&&value>=Long.MIN_VALUE/multiplier){
          emit(value*multiplier);
        }
      }else{emit(value/divisor);}
    }
  }
}
```

Do not embed field.name in source.

- [ ] **Step 5: Run compiler GREEN**

```bash
./gradlew :wow-elasticsearch:test --tests \
  "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationCompilerTest" --stacktrace
```

- [ ] **Step 6: Add date-runtime pagination proof**

Compile a Number DateHistogram plan, mock two pages, capture both SearchRequests:

```kotlin
requests.assert().hasSize(2)
requests.forEach { request ->
    request.runtimeMappings().assert().isEqualTo(plan.runtimeMappings)
    request.runtimeMappings().keys.assert().contains("__wow_date_histogram_0")
}
```

```bash
./gradlew :wow-elasticsearch:test --tests \
  "me.ahoo.wow.elasticsearch.query.snapshot.ElasticsearchAggregationPagerTest" --stacktrace
```

Expected: PASS without changing pager production code.

- [ ] **Step 7: Commit**

```bash
git add wow-elasticsearch/src/main/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompiler.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationCompilerTest.kt \
  wow-elasticsearch/src/test/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchAggregationPagerTest.kt
git commit -m "feat(elasticsearch): compile typed epoch histograms"
```
---

### Task 7: 跨后端 TCK 与 Elasticsearch 真实集成

**Files:**
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt`
- Modify: `test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt`
- Modify: `wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt`

**Interfaces:**
- Produces: shared native DATE、Number(MILLISECONDS)、Number(SECONDS)、date_nanos evidence and Elasticsearch invalid-value/default-template evidence.

- [ ] **Step 1: Write shared TCK tests before adding mock fields**

Milliseconds root field:

```kotlin
@Test
fun `numeric epoch milliseconds should produce portable buckets`() {
    saveAggregationStates(*aggregationStates().toTypedArray())

    aggregation {
        filter { aggregateIds("aggregation-a", "aggregation-b") }
        dateHistogram("snapshotTime", AggregationDateUnit.DAY, "day", ZoneOffset.UTC)
        count("count")
    }.query(snapshotQueryService).test()
        .assertNext { row ->
            row.toMap().assert().isEqualTo(
                mapOf("day" to AGGREGATION_SNAPSHOT_TIME, "count" to 2L),
            )
        }.verifyComplete()
}
```

Add nested seconds field:

```kotlin
dateHistogram(
    LogicalField(
        "createdAtEpochSecond",
        FieldType.Temporal.Number(TimeUnit.SECONDS),
    ),
    AggregationDateUnit.DAY,
    "day",
    ZoneOffset.UTC,
)
```

Add `createdAtNanos` with FieldType.Temporal.Date. Expected keys/counts must equal existing createdAt DATE expectations:
- alpha → 1767398400000/count 1
- beta → 1767312000000/count 2
- delta → 1769990400000/count 1

- [ ] **Step 2: Run integration RED**

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected: snapshotTime case passes; seconds/nanos fields are absent and their new assertions fail.

- [ ] **Step 3: Add deterministic mock fields and ES mappings**

```kotlin
data class MockLine(
    val productId: String,
    val quantity: Int,
    val amount: Double?,
    val createdAt: Instant,
    val discounts: List<MockDiscount>,
    val samples: List<Double> = emptyList(),
    val createdAtEpochSecond: Long = createdAt.epochSecond,
    val createdAtNanos: Instant = createdAt,
)
```

Map createdAtEpochSecond as long and createdAtNanos as date_nanos next to createdAt.

- [ ] **Step 4: Add Elasticsearch invalid-value execution**

Map numeric test fields:

```kotlin
.properties("epoch") { it.long_ { number -> number.ignoreMalformed(true) } }
.properties("epochFraction") { it.double_ { number -> number } }
.properties("epochMulti") { it.long_ { number -> number } }
```

Index/update documents for scalar long, singleton array, malformed string, empty array, multi array, fraction and Long.MAX_VALUE. Query typed Number(MILLISECONDS) and Number(DAYS). Assert only scalar and singleton integral values form buckets.

- [ ] **Step 5: Verify default template time fields**

Using saved real snapshots, run typed Number(MILLISECONDS) queries for firstEventTime, eventTime and snapshotTime:

```kotlin
listOf("firstEventTime", "eventTime", "snapshotTime").forEach { name ->
    aggregation {
        filter { aggregateId(snapshot.aggregateId.id) }
        dateHistogram(
            LogicalField(
                name,
                FieldType.Temporal.Number(TimeUnit.MILLISECONDS),
            ),
            AggregationDateUnit.DAY,
            "day",
            ZoneOffset.UTC,
        )
        count("count")
    }.query(snapshotQueryService).test()
        .assertNext { row ->
            row["day"].assert().isInstanceOf(Long::class.java)
            row["count"].assert().isEqualTo(1L)
        }.verifyComplete()
}
```

Do not add production field-name branches.

- [ ] **Step 6: Run both integrations GREEN**

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected: PASS with real Testcontainers backends.

- [ ] **Step 7: Commit**

```bash
git add test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/mock/MockAggregate.kt \
  test/wow-tck/src/main/kotlin/me/ahoo/wow/tck/query/SnapshotQueryServiceSpec.kt \
  wow-elasticsearch/src/integrationTest/kotlin/me/ahoo/wow/elasticsearch/query/snapshot/ElasticsearchSnapshotQueryServiceTest.kt
git commit -m "test(query): verify typed temporal fields across backends"
```

---

### Task 8: JSON Schema 与 OpenAPI 骨架

**Files:**
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/FieldType.kt`
- Modify: `wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt`
- Modify: `wow-api/src/test/kotlin/me/ahoo/wow/api/query/LogicalFieldTest.kt`
- Modify: `wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt` (deferred import-order gate cleanup only)
- Modify: `schema/query/v2/filter-expression.schema.json`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilder.kt`
- Modify: `wow-schema/src/main/kotlin/me/ahoo/wow/schema/typed/query/LogicalFieldDefinitionProvider.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilderTest.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/typed/query/FilterExpressionDefinitionProviderTest.kt`
- Modify: `wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowModuleTest.kt`
- Modify: `wow-schema/src/test/resources/META-INF/wow-schema-e2e/FilterExpression.json`
- Modify: `wow-schema/src/test/resources/META-INF/wow-schema-e2e/MockStateAggregate.json`
- Modify: `wow-schema/src/test/resources/META-INF/wow-schema-e2e/MockStateAggregateSnapshot.json`
- Modify: `wow-schema/src/test/resources/META-INF/wow-schema-e2e/MockStateAggregateStateEvent.json`
- Modify: `wow-schema/src/test/resources/META-INF/wow-schema/MockStateAggregate.json`
- Modify: `wow-schema/src/test/resources/META-INF/wow-schema/MockStateAggregateSnapshot.json`
- Modify: `wow-schema/src/test/resources/META-INF/wow-schema/MockStateAggregateStateEvent.json`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt`
- Modify: `wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt`
- Modify: `wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json`
- Modify: `docs/superpowers/specs/2026-08-25-temporal-field-type-design.md`
- Modify: `docs/superpowers/plans/2026-08-25-temporal-field-type.md`

**Interfaces:**
- Publishes FieldType→Temporal leaves with discriminator IDs DATE/TEMPORAL_NUMBER/TEMPORAL_STRING and LogicalField string/object shape.
- Old NUMBER/STRING temporal subtype IDs are invalid; no aliases are registered.

- [ ] **Step 1: Write failing API JSON tests**

Update `LogicalFieldTest` expectations before production annotations:

```kotlin
val numeric = LogicalField(
    "snapshotTime",
    FieldType.Temporal.Number(TimeUnit.MILLISECONDS),
)
mapper.writeValueAsString(numeric).assert()
    .contains("\"type\":\"TEMPORAL_NUMBER\"")

val formatted = LogicalField(
    "createdAtText",
    FieldType.Temporal.String(datePattern = "yyyy-MM-dd"),
)
mapper.writeValueAsString(formatted).assert()
    .contains("\"type\":\"TEMPORAL_STRING\"")

listOf("NUMBER", "STRING").forEach { oldId ->
    assertThrows<JacksonException> {
        mapper.readValue(
            """{"name":"field","type":{"type":"$oldId"}}""",
            LogicalField::class.java,
        )
    }
}
```

Keep DATE round-trip unchanged. These tests must fail against the existing NUMBER/STRING annotations before modifying `FieldType.kt`.

- [ ] **Step 2: Write failing static schema tests**

LogicalField payloads:

```kotlin
val payloads = listOf(
    """{"op":"TODAY","field":"state.createdAt"}""",
    """{"op":"TODAY","field":{"name":"state.createdAt","type":{"type":"DATE"}}}""",
    """{"op":"TODAY","field":{"name":"state.epoch","type":{"type":"TEMPORAL_NUMBER","timeUnit":"SECONDS"}}}""",
    """{"op":"TODAY","field":{"name":"state.text","type":{"type":"TEMPORAL_STRING","datePattern":"yyyy-MM-dd"}}}""",
)
payloads.forEach { payload ->
    schema.validate(mapper.readTree(payload)).assert().isEmpty()
}
schema.validate(
    mapper.readTree("""{"op":"TODAY","field":{"type":{"type":"DATE"}}}"""),
).assert().isNotEmpty()

listOf("NUMBER", "STRING").forEach { oldId ->
    schema.validate(
        mapper.readTree("""{"op":"TODAY","field":{"name":"field","type":{"type":"$oldId"}}}"""),
    ).assert().isNotEmpty()
}
```

- [ ] **Step 3: Write failing OpenAPI assertions**

```kotlin
val logicalField = schemas.path("wow.api.query.LogicalField")
logicalField.path("type").toList().map { it.asText() }.assert()
    .containsExactly("string", "object")
logicalField.path("properties").path("name").path("type").asText().assert()
    .isEqualTo("string")
logicalField.path("properties").path("type").isMissingNode.assert().isFalse()

val fieldType = schemas.path("wow.api.query.FieldType")
fieldType.path("oneOf").size().assert().isOne()
val temporal = schemas.path("wow.api.query.FieldType.Temporal")
temporal.path("oneOf").size().assert().isEqualTo(3)
temporal.path("discriminator").path("propertyName").asText().assert()
    .isEqualTo("type")
```

Assert the Temporal discriminator/mapping or leaf schemas publish exactly DATE, TEMPORAL_NUMBER and TEMPORAL_STRING, and the serialized OpenAPI tree contains no old NUMBER/STRING temporal discriminator values. Assert dateFormatter does not occur. Update DateHistogram field description to state only untyped/DATE/TEMPORAL_NUMBER are valid; runtime constructor tests remain the enforcement proof.

- [ ] **Step 4: Run RED**

```bash
./gradlew :wow-api:test --tests \
  "me.ahoo.wow.api.query.LogicalFieldTest" \
  :wow-schema:test --tests \
  "me.ahoo.wow.schema.typed.query.FilterExpressionDefinitionProviderTest" \
  :wow-openapi:test --tests \
  "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" --stacktrace
```

Expected: API still emits NUMBER/STRING and accepts those old IDs; static schema/OpenAPI still lack the new discriminator values and typed LogicalField structure.

- [ ] **Step 5: Update FieldType IDs and static logicalField definition**

Mirror the public hierarchy in Jackson registration while changing the leaf IDs:

```kotlin
@JsonSubTypes(JsonSubTypes.Type(FieldType.Temporal::class))
sealed interface FieldType {
    @JsonSubTypes(
        JsonSubTypes.Type(Temporal.Date::class, name = "DATE"),
        JsonSubTypes.Type(Temporal.Number::class, name = "TEMPORAL_NUMBER"),
        JsonSubTypes.Type(Temporal.String::class, name = "TEMPORAL_STRING"),
    )
    sealed interface Temporal : FieldType
}

@JsonTypeName("TEMPORAL_NUMBER")
data class Number(...)

@JsonTypeName("TEMPORAL_STRING")
data class String(...)
```

Keep Date as DATE and keep all Kotlin type names/hierarchy/backend behavior unchanged. Register no NUMBER/STRING aliases.

Update the existing `LogicalFieldDefinitionProvider` to reuse the standard object definition, expose both
`string` and `object` types, retain the `FieldType` reference, and publish `additionalProperties: false`. In `OpenAPISchemaBuilder`, normalize an
annotated single `$ref` into `oneOf: [$ref]`, matching the same existing normalization used for generated
`anyOf`; add a generic regression test and retain the AggregationExpression regression.

Then update static schema using oneOf:

Use oneOf:

```json
{
  "oneOf": [
    {
      "type": "string",
      "pattern": "@?[A-Za-z_][A-Za-z0-9_-]*(\\\\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*"
    },
    {
      "type": "object",
      "additionalProperties": false,
      "required": ["name"],
      "properties": {
        "name": {
          "type": "string",
          "pattern": "@?[A-Za-z_][A-Za-z0-9_-]*(\\\\.(?:@?[A-Za-z_][A-Za-z0-9_-]*|[0-9]+))*"
        },
        "type": {"$ref": "#/definitions/fieldType"}
      }
    }
  ]
}
```

Define `fieldType` as a oneOf containing only `#/definitions/temporalFieldType`; define `temporalFieldType` as DATE/TEMPORAL_NUMBER/TEMPORAL_STRING oneOf. TEMPORAL_NUMBER timeUnit default is MILLISECONDS; TEMPORAL_STRING requires datePattern. Replace RelativeTime flat datePattern/timeUnit properties with LogicalField.type.

- [ ] **Step 6: Refresh wow-schema snapshots**

Run focused tests, then update exactly:
- `FilterExpression.json`
- `MockStateAggregate.json`
- `MockStateAggregateSnapshot.json`
- `MockStateAggregateStateEvent.json`

```bash
./gradlew :wow-schema:test --tests \
  "me.ahoo.wow.schema.e2e.E2ESchemaGeneratorTest" --stacktrace
```

Rerun until PASS.

- [ ] **Step 7: Refresh OpenAPI snapshot**

```bash
./gradlew -Dwow.snapshot.update=true :wow-openapi:test --tests \
  "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest.generated openapi should match example domain compatibility snapshot"
./gradlew :wow-openapi:test --tests \
  "me.ahoo.wow.openapi.snapshot.OpenApiCompatibilitySnapshotTest" --stacktrace
```

Review the snapshot diff: only FieldType, LogicalField object form, RelativeTime constructor shape, DateHistogram/timeZone and mock test fields are allowed.

- [ ] **Step 8: Prove old temporal IDs are absent**

```bash
rg -n 'name = "(NUMBER|STRING)"|JsonTypeName\("(NUMBER|STRING)"\)|"type"\s*:\s*"(NUMBER|STRING)"|\\"type\\":\\"(NUMBER|STRING)\\"' \
  wow-api wow-schema wow-openapi schema documentation docs/superpowers
```

Expected: no matches. Semantic Kotlin names Number/String and prose about numeric/string storage remain valid; only old discriminator values are forbidden.

- [ ] **Step 9: Run checks and commit**

```bash
./gradlew :wow-api:check :wow-schema:check :wow-openapi:check --stacktrace

git add wow-api/src/main/kotlin/me/ahoo/wow/api/query/FieldType.kt \
  wow-api/src/main/kotlin/me/ahoo/wow/api/query/AggregationQuery.kt \
  wow-api/src/test/kotlin/me/ahoo/wow/api/query/LogicalFieldTest.kt \
  wow-query/src/main/kotlin/me/ahoo/wow/query/dsl/FilterDsl.kt \
  schema/query/v2/filter-expression.schema.json \
  wow-schema/src/main/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilder.kt \
  wow-schema/src/main/kotlin/me/ahoo/wow/schema/typed/query/LogicalFieldDefinitionProvider.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/openapi/OpenAPISchemaBuilderTest.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/typed/query/FilterExpressionDefinitionProviderTest.kt \
  wow-schema/src/test/kotlin/me/ahoo/wow/schema/WowModuleTest.kt \
  wow-schema/src/test/resources/META-INF/wow-schema-e2e/FilterExpression.json \
  wow-schema/src/test/resources/META-INF/wow-schema-e2e/MockStateAggregate.json \
  wow-schema/src/test/resources/META-INF/wow-schema-e2e/MockStateAggregateSnapshot.json \
  wow-schema/src/test/resources/META-INF/wow-schema-e2e/MockStateAggregateStateEvent.json \
  wow-schema/src/test/resources/META-INF/wow-schema/MockStateAggregate.json \
  wow-schema/src/test/resources/META-INF/wow-schema/MockStateAggregateSnapshot.json \
  wow-schema/src/test/resources/META-INF/wow-schema/MockStateAggregateStateEvent.json \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/ExampleDomainOpenAPITest.kt \
  wow-openapi/src/test/kotlin/me/ahoo/wow/openapi/snapshot/OpenApiCompatibilitySnapshotTest.kt \
  wow-openapi/src/test/resources/openapi/example-domain-openapi.snapshot.json \
  docs/superpowers/specs/2026-08-25-temporal-field-type-design.md \
  docs/superpowers/plans/2026-08-25-temporal-field-type.md
git commit -m "feat(schema): publish typed logical fields"
```

---

### Task 9: 文档、最终验证与独立 PR

**Files:**
- Modify: `documentation/docs/en/guide/query.md`
- Modify: `documentation/docs/zh/guide/query.md`
- Verify: all feature files against origin/main

**Interfaces:**
- Documents the final public JSON/DSL contract; does not add product interfaces.
- Produces a reviewed, verified, unmerged PR based on the latest `origin/main`.

- [ ] **Step 1: Update both guides**

Both must document:
- LogicalField string and `{name,type}` forms.
- FieldType root currently has only Temporal; Temporal discriminator values are DATE/TEMPORAL_NUMBER/TEMPORAL_STRING.
- Kotlin leaf names remain Number/String, and old NUMBER/STRING discriminator values are not accepted.
- Untyped temporal fields default Number(MILLISECONDS).
- RelativeTime accepts all Temporal; DateHistogram rejects String.
- system default timeZone and explicit-zone recommendation.
- snapshotTime typed TEMPORAL_NUMBER/MILLISECONDS examples.
- Number accepts exactly one numeric scalar or singleton array; empty and multi arrays do not form buckets.
- bucket keys remain epoch-millisecond bucket starts.
- backend mapping/runtime semantics and TEMPORAL_STRING format limitation.

- [ ] **Step 2: Build docs**

```bash
pnpm --dir documentation docs:build
```

Expected: PASS.

- [ ] **Step 3: Commit docs**

```bash
git add documentation/docs/en/guide/query.md documentation/docs/zh/guide/query.md
git commit -m "docs(query): explain typed temporal fields"
```

- [ ] **Step 4: Run module checks**

```bash
./gradlew :wow-api:check :wow-query:check \
  :wow-mongo:check :wow-elasticsearch:check \
  :wow-schema:check :wow-openapi:check --stacktrace
```

Expected: PASS.

- [ ] **Step 5: Run real integrations**

```bash
./gradlew :wow-mongo:integrationTest :wow-elasticsearch:integrationTest --stacktrace
```

Expected: PASS; record test counts and elapsed time.

- [ ] **Step 6: Run Detekt and diff checks**

```bash
./gradlew detekt --stacktrace
git diff --check origin/main...HEAD
git status --short
git diff --stat origin/main...HEAD
```

Expected: PASS/clean; no template, dependency, module, config or release changes.

- [ ] **Step 7: Verification and code review**

Use `superpowers:verification-before-completion` with fresh Step 2-6 evidence, then `superpowers:requesting-code-review` for `origin/main...HEAD`. Fix only validated findings with narrow RED/GREEN tests; repeat Steps 4-6 after source changes.

- [ ] **Step 8: Refresh main and reverify if changed**

```bash
git fetch origin main
git rebase origin/main
```

If the base changes, rerun Steps 2 and 4-6.

- [ ] **Step 9: Push and create PR**

```bash
git push -u origin feat/temporal-field-type
gh pr create \
  --base main \
  --head feat/temporal-field-type \
  --title "feat(query): declare typed temporal fields" \
  --body-file - <<'EOF'
## Summary
- add FieldType -> Temporal and LogicalField string/object JSON
- refactor RelativeTimeFilter around typed LogicalField
- support DATE and numeric epoch DateHistogram in MongoDB and Elasticsearch
- publish JSON Schema/OpenAPI and bilingual documentation

## Validation
- focused module checks and Detekt
- real MongoDB and Elasticsearch integrationTest
- VitePress documentation build
- cross-backend Number(MILLISECONDS) TCK

## Compatibility
FilterExpression and AggregationQuery are recent protocols and do not retain old source/wire constructors. Condition conversion preserves String patterns and runtime DateTimeFormatter semantics.

## Evidence boundary
This PR does not publish a release, deploy services, migrate indices, or provide production proof.
EOF
```

Do not merge.

- [ ] **Step 10: Report PR readiness**

Report URL, head SHA, local checks, real integrations, docs build, remote CI state, compatibility scope and unmerged status.

---

## Plan Self-Review

- [x] Spec coverage: FieldType skeleton, LogicalField dual JSON, RelativeTime, both backends, invalid values, TCK, schema/OpenAPI, docs and PR each map to a task.
- [x] Placeholder scan: no unfinished marker, generic error-handling instruction, unspecified file path or deferred implementation remains.
- [x] Type consistency: all tasks use `FieldType.Temporal.*`, `LogicalField.name/type`, and `resolveTemporal(LogicalField, Boolean)`.
- [x] Scope: no AUTO, extra FieldType child, Catalog, dependency, template edit, migration, persistent runtime field or compatibility creator outside LogicalField.
- [x] Evidence: behavior tasks include RED/GREEN; cross-backend integration and final verification are separate gates.
