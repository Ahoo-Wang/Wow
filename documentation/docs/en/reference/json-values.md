---
title: JSON Values
description: How Wow's JSON writes each platform value class a state, a command or an event holds, the schema type and format the Manifest gives it, and what it reads besides and refuses.
---

# JSON Values

Wow's JSON (`WowJson`) writes and reads the states, commands and events of a service: in the event store, in snapshots, on Kafka and over HTTP. This page lists each platform value class it writes as a scalar, with the JSON type and format the Manifest's schema gives the class and the text Wow's JSON writes for it.

- The classes are the JVM's: a Kotlin `Int` is `int`, or `Integer` where it is nullable or a type argument, and a `ByteArray` is `byte[]`. A row matches its class itself, not a subclass.
- A JavaScript `number` holds an integer exactly only up to 2^53 − 1 (`9007199254740991`): a client reads a larger `long` as a `bigint`.
- An enumeration is written by its name, as its schema's `enum` lists it: a `Month` as `"JANUARY"`.
- A property holding another platform class written as a scalar (a `Charset`, a `Path`, an `AtomicLong`) stops the build, unless its schema is declared ([Manifest](../guide/manifest#a-type-written-by-a-custom-serializer)).
- A test holds each row to the code: its classes, JSON type and format to what the Manifest describes, and each value it shows to what Wow's JSON writes.

The written form is what Wow promises, and what a client that is not Wow writes. The last column says what Wow's JSON reads besides it and what it refuses, and a test holds each:

- A value of another JSON kind than its class's is refused, and so is an empty string for a `java.time` value, a `URL`, a `Currency` or a `Character`, which Jackson would read as `null`. A command whose body holds one is refused as `wow.command.malformed`, with one violation: the property's path in the body (`items[0].quantity`) and its code, `wrong-kind`, `out-of-range`, `invalid-format` or `not-in-enumeration`, with a message that names the kinds expected and given.
- Wow 1.x to 6.x ran on Jackson 2, which wrote dates, times and durations as numbers and arrays. Each of those forms reads, as its row says, so a store carried from those releases reads; any other number or array for those classes is refused.
- A `kotlin.time.Duration` refuses a number: it is ISO-8601 text.
- A `Month` reads its name or its one-based number, and refuses `0`.

| Class | JSON | Written | Read besides, and refused |
| --- | --- | --- | --- |
| `boolean`, `Boolean` | `boolean` | `true`, `false` | Refuses a number, `1`, and a string, `"true"`; `null` for a `boolean`. |
| `int`, `Integer` | `integer`, `int32` | `1`, `2147483647`, `-2147483648` | Refuses a float, `1.5`, `1e2` or `1.0`, and a string, `"7"`; a number out of range, `2147483648`. |
| `short`, `Short` | `integer`, `int32` | `1`, `-32768`, `32767` | As an `int`. Refuses `40000`. |
| `byte`, `Byte` | `integer`, `int32` | `1`, `-128`, `127` | Refuses a number beyond `-128` to `127`, `200`, which Jackson would wrap to `-56`. |
| `Year` | `integer`, `int32` | `2026`, `0`, `-1`, `12026` | Refuses a string, `"2026"`, and a year beyond ±999,999,999. |
| `long`, `Long` | `integer`, `int64` | `1`, `9223372036854775807` | As an `int`. Refuses `9223372036854775808`. |
| `BigInteger` | `integer`, `bigint` | `123456789012345678901234567890`, of any length | Refuses a float, `1.5` or `1e3`, and a string, `"12"`. |
| `float`, `Float` | `number`, `float` | `1.5`, `0.1`, `3.4028235E38`, `-0.0`; not-a-number and the infinities as strings, `"NaN"`, `"Infinity"`, `"-Infinity"` | Reads the three strings, and refuses any other. Reads `1e39` as `Infinity`, as IEEE 754 rounds it. |
| `double`, `Double` | `number` | `0.1`, `1.0`, `1.0E300`, `-0.0`; not-a-number and the infinities as strings, `"NaN"`, `"Infinity"`, `"-Infinity"` | Reads the three strings, and refuses any other, `"1.5"`. Reads `1e400` as `Infinity`, as IEEE 754 rounds it. |
| `BigDecimal` | `number`, `decimal` | Its digits and scale, a trailing zero kept, `2.50`, `0.000001`; a negative scale or a very small value in exponent form, `1E+3`, `1E-7` | Refuses a string, `"2.50"`. Reads `1e400` exactly, with no overflow. |
| `Instant` | `string`, `date-time` | At UTC, `"2026-01-02T03:04:05Z"`; its fraction to the nanosecond as needed, `"2026-01-02T03:04:05.123456789Z"`; a year past 9999 signed, `"+12026-01-01T00:00:00Z"`, and one before 1, `"-0001-01-01T00:00:00Z"` | Reads an offset, converted to UTC (`"2026-01-02T03:04:05+08:00"` is `"2026-01-01T19:04:05Z"`), and lower-case `t` and `z`. Reads a decimal of epoch seconds, its fraction as nanoseconds (`1767323045.123456789`), the form Wow 1.x to 6.x wrote; an integer reads as epoch seconds too, which none of those releases wrote with its default configuration. Refuses an array, a time with no offset and a date alone. |
| `OffsetDateTime` | `string`, `date-time` | Its offset kept, `"2026-01-02T03:04:05+08:00"` | Reads it back at the offset it was written with. Reads a decimal of epoch seconds (`1767294245.5`), the form Wow 1.x to 6.x wrote: such a stored value carries no offset, and reads at UTC. Refuses an array and a time with no offset. |
| `ZonedDateTime` | `string`, `date-time` | Its offset, not its zone: Asia/Shanghai's 03:04:05 on 2 January 2026 is `"2026-01-02T03:04:05+08:00"` | Reads it back at its offset, a fixed one: the region is not written. Reads a zone in brackets, `"2026-01-02T03:04:05+08:00[Asia/Shanghai]"`, keeping it. Reads a decimal of epoch seconds, the form Wow 1.x to 6.x wrote: such a stored value carries no offset or zone, and reads at UTC. Refuses an array. |
| `Date` | `string`, `date-time` | At UTC, the milliseconds always written, `"2026-01-02T03:04:05.123Z"` | Reads an offset, and epoch milliseconds (`1767323045123`), the form Wow 1.x to 6.x wrote. Refuses a date alone (`"2026-01-02"`), a fraction of a millisecond, an array and other text. |
| `Calendar` | `string`, `date-time` | As a Date is, at UTC: its time zone is not written, `"2026-01-02T03:04:05.123Z"` | As a `Date`: reads epoch milliseconds, the form Wow 1.x to 6.x wrote, and refuses a date alone. Reads it back at UTC. |
| `LocalDate` | `string`, `date` | `"2026-01-02"`; a year past 9999 signed, `"+12026-01-01"`; a negative one, `"-0001-01-01"` | Reads `[2026,1,2]`, the form Wow 1.x to 6.x wrote. Refuses a date-time (`"2026-01-02T03:04:05"`), a number, a year beyond 9999 with no sign (`"12026-01-01"`), `"2026-1-2"` and a day that does not exist, `"2026-02-30"`. |
| `LocalTime` | `string`, `time` | The seconds always written, `"03:04:05"`, `"03:04:00"`; the fraction as needed, `"03:04:05.12"` | Reads `[3,4,5,123000000]`, the form Wow 1.x to 6.x wrote. Refuses a number, `"3:04"` and an offset. |
| `OffsetTime` | `string`, `time` | `"03:04:05+08:00"`; zero seconds left out, `"03:04Z"` | Reads `[3,4,5,"+08:00"]`, the form Wow 1.x to 6.x wrote. Refuses a number and a time with no offset. |
| `Duration` | `string`, `duration` | ISO-8601, `"PT1H30M"`, `"PT0S"`; days as hours, `"PT48H"`; a negative one, `"PT-1.5S"`; to the nanosecond, `"PT0.000000001S"` | Reads days, `"P1D"` as `"PT24H"`, lower case, and a decimal of seconds (`5400.000000000`), the form Wow 1.x to 6.x wrote; an integer of seconds too. Refuses an array, months and years (`"P1M"`) and any other text (`"90m"`). |
| `Period` | `string`, `duration` | ISO-8601, `"P1Y2M3D"`, `"P0D"`; weeks as days, `"P14D"`; a negative one, `"P-1M"` | Reads weeks, `"P2W"`. Refuses a time (`"PT1H"`), a number and an array. |
| `kotlin.time.Duration` | `string`, `duration` | As a Duration is, `"PT1H30M"`; a negative one, `"PT-1H-30M"`; the infinite one, `"PT2562047788015215H30M7S"` | Reads days. Reads that text, or a longer one, as `INFINITE`. Refuses Kotlin's own form (`"1h 30m"`), months and a number. |
| `UUID` | `string`, `uuid` | Lower case, 36 characters, `"00000000-0000-0001-0000-000000000002"` | Reads upper case. Refuses any other form. |
| `URI` | `string`, `uri` | As it is, encoded, `"https://example.com/a%20b"`, `"urn:isbn:123"`, `"/relative"` | Reads `""` as an empty URI. Refuses an illegal character, `"a b"`. |
| `URL` | `string`, `uri` | `"https://example.com/a"` | Refuses `""`, which Jackson would read as `null`, and text with no protocol, `"example.com"`. |
| `byte[]` | `string`, `byte` | Base64 with padding, `"AQID"`, `"//79/A=="`; empty, `""` | Refuses an array of numbers, `[1,2,3]`, a missing padding (`"AQI"`) and the URL-safe alphabet (`-`, `_`). |
| `String` | `string` | As it is, escaped as JSON escapes it, `"\u0000"` | Refuses a number or a boolean, `1`, and an object. |
| `char`, `Character` | `string` | One character, `"c"` | Refuses a number, `65`, `""` and more than one character. |
| `LocalDateTime` | `string` | The seconds always written, `"2026-01-02T03:04:05"`; a year past 9999 signed, `"+12026-01-01T00:00:00"` | Reads `[2026,1,2,3,4,5,123000000]`, the form Wow 1.x to 6.x wrote. Refuses a trailing `Z` or an offset (`"+08:00"`), a number and a space for the `T`. |
| `YearMonth` | `string` | `"2026-01"`, `"0005-01"`; a year past 9999 with no sign, `"12026-01"`; a negative one, `"-0001-01"` | Reads a year of fewer digits, `"5-01"`, and `[2026,1]`, the form Wow 1.x to 6.x wrote. Refuses a number, a plus sign (`"+12026-01"`), `"2026-1"` and month 13. |
| `MonthDay` | `string` | `"--01-02"`, `"--02-29"` | Reads `[1,2]`. Refuses `"01-02"` and a day that does not exist, `"--02-30"`. |
| `ZoneId` | `string` | `"Asia/Shanghai"`, `"UTC"`, `"Z"`, `"GMT+08:00"` | Reads an offset, `"+08:00"`. Refuses an unknown region and one in other case, `"asia/shanghai"`. |
| `ZoneOffset` | `string` | `"+08:00"`, `"+05:30"`, `"-18:00"`; zero as `"Z"`; seconds as needed, `"+01:02:03"` | Reads `"+8"`, and `"+00:00"` as `"Z"`. Refuses an offset beyond ±18:00 (`"+18:30"`) and a region, `"UTC"`. |
| `TimeZone` | `string` | Its id, `"UTC"`, `"Asia/Shanghai"`, `"GMT+08:00"` | Reads an offset, `"+08:00"`, as `"GMT+08:00"`, and a short id Java knows, `"PST"`. Refuses an unknown id. |
| `Locale` | `string` | A BCP 47 tag, `"zh-CN"`, `"zh-Hans-CN"`, `"en-US-POSIX"`; the root locale as `""` | Refuses nothing: reads `"zh_CN"` as `"zh-CN"`, and any other text; one that is no tag is written back as `"und"`. |
| `Currency` | `string` | Its ISO 4217 code, `"CNY"` | Refuses `""`, which Jackson would read as `null`, an unknown code and one in lower case, `"cny"`. |
