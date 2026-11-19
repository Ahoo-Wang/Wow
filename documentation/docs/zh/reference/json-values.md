---
title: JSON 中的值
description: Wow 的 JSON 如何写出状态、命令和事件所持有的每个平台值类，以及 Manifest 的 schema 给它的类型和格式。
---

# JSON 中的值

Wow 的 JSON（`WowJson`）写出并读取服务的状态、命令和事件：在事件存储中、在快照中、在 Kafka 上和经由 HTTP。本页列出它写成标量的每个平台值类，以及 Manifest 的 schema 为这个类给出的 JSON 类型和格式，和 Wow 的 JSON 为它写出的文本。

- 这些类是 JVM 的类：Kotlin 的 `Int` 是 `int`，可空或作为类型实参时是 `Integer`；`ByteArray` 是 `byte[]`。一行只匹配它的类本身，不匹配子类。
- JavaScript 的 `number` 只能精确持有至多 2^53 − 1（`9007199254740991`）的整数：客户端把更大的 `long` 读作 `bigint`。
- 枚举按名字写出，与其 schema 的 `enum` 列出的一样：`Month` 写作 `"JANUARY"`。
- 属性若持有另一个写成标量的平台类（`Charset`、`Path`、`AtomicLong`），构建会停下，除非声明了它的 schema（[Manifest](../guide/manifest#a-type-written-by-a-custom-serializer)）。
- 有一个测试让每一行与代码一致：它的类、JSON 类型和格式与 Manifest 的描述一致，它列出的每个值与 Wow 的 JSON 写出的一致。

写出的形式是 Wow 承诺的，也是不是 Wow 的客户端应当写出的。Wow 的 JSON 还会按其 JSON 库的默认行为读取一些别的形式：这些不在承诺之内，正在逐一决定（[#6488](https://github.com/Ahoo-Wang/Wow/issues/6488)）。有两项拒绝已经决定，并由测试保证：

- `kotlin.time.Duration` 拒绝数字：它是 ISO-8601 文本。
- `Month` 读取它的名字或从 1 开始的序号，拒绝 `0`。

| 类 | JSON | 写出 |
| --- | --- | --- |
| `boolean`、`Boolean` | `boolean` | `true`、`false` |
| `int`、`Integer` | `integer`、`int32` | `1`、`2147483647`、`-2147483648` |
| `short`、`Short` | `integer`、`int32` | `1`、`-32768`、`32767` |
| `byte`、`Byte` | `integer`、`int32` | `1`、`-128`、`127` |
| `Year` | `integer`、`int32` | `2026`、`0`、`-1`、`12026` |
| `long`、`Long` | `integer`、`int64` | `1`、`9223372036854775807` |
| `BigInteger` | `integer`、`bigint` | `123456789012345678901234567890`，任意长度 |
| `float`、`Float` | `number`、`float` | `1.5`、`0.1`、`3.4028235E38`、`-0.0`；非数和无穷写作字符串，`"NaN"`、`"Infinity"`、`"-Infinity"` |
| `double`、`Double` | `number` | `0.1`、`1.0`、`1.0E300`、`-0.0`；非数和无穷写作字符串，`"NaN"`、`"Infinity"`、`"-Infinity"` |
| `BigDecimal` | `number`、`decimal` | 它的数字与标度，保留末尾的零，`2.50`、`0.000001`；负标度或很小的值写成指数形式，`1E+3`、`1E-7` |
| `Instant` | `string`、`date-time` | UTC，`"2026-01-02T03:04:05Z"`；小数部分按需写到纳秒，`"2026-01-02T03:04:05.123456789Z"`；9999 之后的年份带符号，`"+12026-01-01T00:00:00Z"`，1 之前的，`"-0001-01-01T00:00:00Z"` |
| `OffsetDateTime` | `string`、`date-time` | 保留偏移，`"2026-01-02T03:04:05+08:00"` |
| `ZonedDateTime` | `string`、`date-time` | 写出偏移，不写时区：Asia/Shanghai 2026 年 1 月 2 日 03:04:05 写作 `"2026-01-02T03:04:05+08:00"` |
| `Date` | `string`、`date-time` | UTC，总是写出毫秒，`"2026-01-02T03:04:05.123Z"` |
| `Calendar` | `string`、`date-time` | 与 Date 相同，为 UTC：不写出它的时区，`"2026-01-02T03:04:05.123Z"` |
| `LocalDate` | `string`、`date` | `"2026-01-02"`；9999 之后的年份带符号，`"+12026-01-01"`；负年份，`"-0001-01-01"` |
| `LocalTime` | `string`、`time` | 总是写出秒，`"03:04:05"`、`"03:04:00"`；小数部分按需，`"03:04:05.12"` |
| `OffsetTime` | `string`、`time` | `"03:04:05+08:00"`；秒为零时省略，`"03:04Z"` |
| `Duration` | `string`、`duration` | ISO-8601，`"PT1H30M"`、`"PT0S"`；天写作小时，`"PT48H"`；负值，`"PT-1.5S"`；精确到纳秒，`"PT0.000000001S"` |
| `Period` | `string`、`duration` | ISO-8601，`"P1Y2M3D"`、`"P0D"`；周写作天，`"P14D"`；负值，`"P-1M"` |
| `kotlin.time.Duration` | `string`、`duration` | 与 Duration 相同，`"PT1H30M"`；负值，`"PT-1H-30M"`；无穷大，`"PT2562047788015215H30M7S"` |
| `UUID` | `string`、`uuid` | 小写，36 个字符，`"00000000-0000-0001-0000-000000000002"` |
| `URI` | `string`、`uri` | 原样，已编码，`"https://example.com/a%20b"`、`"urn:isbn:123"`、`"/relative"` |
| `URL` | `string`、`uri` | `"https://example.com/a"` |
| `byte[]` | `string`、`byte` | 带填充的 Base64，`"AQID"`、`"//79/A=="`；空数组，`""` |
| `String` | `string` | 原样，按 JSON 的方式转义，`"\u0000"` |
| `char`、`Character` | `string` | 一个字符，`"c"` |
| `LocalDateTime` | `string` | 总是写出秒，`"2026-01-02T03:04:05"`；9999 之后的年份带符号，`"+12026-01-01T00:00:00"` |
| `YearMonth` | `string` | `"2026-01"`、`"0005-01"`；9999 之后的年份不带符号，`"12026-01"`；负年份，`"-0001-01"` |
| `MonthDay` | `string` | `"--01-02"`、`"--02-29"` |
| `ZoneId` | `string` | `"Asia/Shanghai"`、`"UTC"`、`"Z"`、`"GMT+08:00"` |
| `ZoneOffset` | `string` | `"+08:00"`、`"+05:30"`、`"-18:00"`；零写作 `"Z"`；秒按需，`"+01:02:03"` |
| `TimeZone` | `string` | 它的 ID，`"UTC"`、`"Asia/Shanghai"`、`"GMT+08:00"` |
| `Locale` | `string` | BCP 47 标签，`"zh-CN"`、`"zh-Hans-CN"`、`"en-US-POSIX"`；根语言环境写作 `""` |
| `Currency` | `string` | ISO 4217 代码，`"CNY"` |
