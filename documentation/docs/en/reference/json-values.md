---
title: JSON Values
description: How Wow's JSON writes each platform value class a state, a command or an event holds, the schema type and format the Manifest gives it, and what its reader refuses.
---

# JSON Values

Wow's JSON (`WowJson`) writes and reads the states, commands and events of a service: in the event store, in snapshots, on Kafka and over HTTP. This page lists each platform value class it writes as a scalar. Each row gives the JSON type and format the Manifest's schema gives the class, the text it writes for an ordinary value and for the edges, and what it reads besides that text and what it refuses. A client that is not Wow, a TypeScript one say, writes what the third column shows and reads what it shows.

- The classes are the JVM's: a Kotlin `Int` is `int`, or `Integer` where it is nullable or a type argument, and a `ByteArray` is `byte[]`. A row matches its class itself, not a subclass.
- An enumeration is written by its name, as its schema's `enum` lists it: a `Month` as `"JANUARY"`.
- A property holding another platform class written as a scalar (a `Charset`, a `Path`, an `AtomicLong`) stops the build, unless its schema is declared ([Manifest](../guide/manifest#a-type-written-by-a-custom-serializer)).
- A test fails when this table and the classes the Manifest describes differ.

| Class | JSON | Written | Read besides, and refused |
| --- | --- | --- | --- |
| `boolean`, `Boolean` | `boolean` | `true`, `false` | Reads `1` and `"true"`. Refuses `null` for a `boolean`. |
| `int`, `Integer` | `integer`, `int32` | `1`, `2147483647`, `-2147483648` | Reads `1.5` as `1` (truncated), `1e2` as `100`, `"7"` as `7`. Refuses a number out of range, `2147483648`. |
| `short`, `Short` | `integer`, `int32` | `1`, `-32768` to `32767` | As an `int`. Refuses `40000`. |
| `byte`, `Byte` | `integer`, `int32` | `1`, `-128` to `127` | Reads up to `255`, wrapping `200` to `-56`. Refuses `256` and `-129`. |
| `Year` | `integer`, `int32` | `2026`, `0`, `-1`, `12026` | Reads `"2026"`. Refuses a year beyond ±999,999,999. |
| `long`, `Long` | `integer`, `int64` | `1`, `9223372036854775807` | As an `int`. Refuses `9223372036854775808`. A JavaScript `number` holds an integer exactly only up to 2^53 − 1 (`9007199254740991`): read a larger one as a `bigint`. |
| `BigInteger` | `integer`, `bigint` | `123456789012345678901234567890`, of any length | Reads `1.5` as `1`, `1e3` as `1000`, `"12"` as `12`. |
| `float`, `Float` | `number`, `float` | `1.5`, `0.1`, `3.4028235E38`, `-0.0`; not-a-number and the infinities as the strings `"NaN"`, `"Infinity"`, `"-Infinity"` | Reads the three strings. Reads `1e39` as `Infinity`. |
| `double`, `Double` | `number` | `0.1`, `1.0`, `1.0E300`, `-0.0`; `"NaN"`, `"Infinity"`, `"-Infinity"` as a `float`'s | Reads the three strings and `"1.5"`. Reads `1e400` as `Infinity`. |
| `BigDecimal` | `number`, `decimal` | Its digits and scale: `2.50` keeps its trailing zero, `0.000001`; a negative scale or a very small value in exponent form, `1E+3`, `1E-7` | Reads `"2.50"`. Reads `1e400` exactly, with no overflow. |
| `Instant` | `string`, `date-time` | `"2026-01-02T03:04:05Z"`, always at UTC; its fraction to the nanosecond as needed, `"2026-01-02T03:04:05.123456789Z"`; a year beyond 9999 signed, `"+12026-01-01T00:00:00Z"`, and one before 1 `"-0001-01-01T00:00:00Z"` | Reads an offset, converted to UTC (`"2026-01-02T03:04:05+08:00"` is `"2026-01-01T19:04:05Z"`), and lower-case `t` and `z`. Reads a number as epoch seconds, its fraction as nanoseconds (`1767323045.5`). Refuses a time with no offset and a date alone. |
| `OffsetDateTime` | `string`, `date-time` | `"2026-01-02T03:04:05+08:00"`, its offset kept | Reads it back at UTC, `"2026-01-01T19:04:05Z"`: the same instant, the offset lost. Reads a number as epoch seconds. Refuses a time with no offset. |
| `ZonedDateTime` | `string`, `date-time` | `"2026-01-02T03:04:05+08:00"`: its offset, not its zone | Reads it back at UTC, the zone lost. Reads a zone in brackets, `"2026-01-02T03:04:05+08:00[Asia/Shanghai]"`, at UTC too. |
| `Date` | `string`, `date-time` | `"2026-01-02T03:04:05.123Z"`, at UTC, the milliseconds always written | Reads an offset, a date alone at UTC's midnight, and a number as epoch milliseconds. Refuses other text. |
| `Calendar` | `string`, `date-time` | As a `Date`, at UTC: its time zone is not written | Reads it back at UTC. |
| `LocalDate` | `string`, `date` | `"2026-01-02"`; a year beyond 9999 signed, `"+12026-01-01"`; a negative one `"-0001-01-01"` | Reads a date-time, dropping the time, `[2026,1,2]`, and a number as an epoch day. Refuses a year beyond 9999 with no sign (`"12026-01-01"`), `"2026-1-2"` and a day that does not exist, `"2026-02-30"`. |
| `LocalTime` | `string`, `time` | `"03:04:05"`, the seconds always written; the fraction as needed, `"03:04:05.12"` | Refuses `"3:04"` and an offset. |
| `OffsetTime` | `string`, `time` | `"03:04:05+08:00"`; zero seconds left out, `"03:04Z"` | Refuses a time with no offset. |
| `Duration` | `string`, `duration` | ISO-8601: `"PT1H30M"`, `"PT0S"`; days as hours, `"PT48H"`; a negative one `"PT-1.5S"`; to the nanosecond, `"PT0.000000001S"` | Reads days, `"P1D"` as `"PT24H"`, lower case, and a number as seconds (`5400.5`). Refuses months and years (`"P1M"`) and any other text (`"90m"`). |
| `Period` | `string`, `duration` | ISO-8601: `"P1Y2M3D"`, `"P0D"`; weeks as days, `"P14D"`; a negative one `"P-1M"` | Reads weeks, `"P2W"`. Refuses a time (`"PT1H"`) and a number. |
| `kotlin.time.Duration` | `string`, `duration` | As a `Duration`: `"PT1H30M"`, a negative one `"PT-1H-30M"`; `INFINITE` as `"PT2562047788015215H30M7S"` | Reads days. Reads that text, or a longer one, as `INFINITE`. Refuses Kotlin's own form (`"1h 30m"`), months and a number. |
| `UUID` | `string`, `uuid` | Lower case, 36 characters: `"00000000-0000-0001-0000-000000000002"` | Reads upper case. Refuses any other form. |
| `URI` | `string`, `uri` | As it is, encoded: `"https://example.com/a%20b"`, `"urn:isbn:123"`, `"/relative"` | Reads `""` as an empty URI. Refuses an illegal character, `"a b"`. |
| `URL` | `string`, `uri` | `"https://example.com/a"` | Reads `""` as `null`. Refuses text with no protocol, `"example.com"`. |
| `byte[]` | `string`, `byte` | Base64 with padding: `"AQID"`, `"//79/A=="`; empty as `""` | Reads an array of numbers, `[1,2,3]`. Refuses a missing padding (`"AQI"`) and the URL-safe alphabet (`-`, `_`). |
| `String` | `string` | As it is, escaped as JSON escapes it, `"\u0000"` | Reads a number or a boolean as its text, `1` as `"1"`. Refuses an object. |
| `char`, `Character` | `string` | One character, `"c"` | Reads a number as its code, `65` as `"A"`, and `""` as `null`. Refuses more than one character. |
| `LocalDateTime` | `string` | `"2026-01-02T03:04:05"`, the seconds always written; a year beyond 9999 signed, `"+12026-01-01T00:00:00"` | Reads a trailing `Z`, dropping it. Refuses an offset (`"+08:00"`) and a space for the `T`. |
| `YearMonth` | `string` | `"2026-01"`, `"0005-01"`; a year past 9999 with no plus sign, `"12026-01"`; a negative one `"-0001-01"` | Reads a year of fewer digits, `"5-01"`, and `[2026,1]`. Refuses a plus sign (`"+12026-01"`), `"2026-1"` and month 13. |
| `MonthDay` | `string` | `"--01-02"`, `"--02-29"` | Refuses `"01-02"` and a day that does not exist, `"--02-30"`. |
| `ZoneId` | `string` | `"Asia/Shanghai"`, `"UTC"`, `"Z"`, `"GMT+08:00"` | Reads an offset, `"+08:00"`. Refuses an unknown region and one in other case, `"asia/shanghai"`. |
| `ZoneOffset` | `string` | `"+08:00"`, `"+05:30"`, `"-18:00"`; zero as `"Z"`; seconds as needed, `"+01:02:03"` | Reads `"+8"`, and `"+00:00"` as `"Z"`. Refuses an offset beyond ±18:00 (`"+18:30"`) and a region, `"UTC"`. |
| `TimeZone` | `string` | Its id: `"UTC"`, `"Asia/Shanghai"`, `"GMT+08:00"` | Refuses nothing: an unknown id, or an offset alone (`"+08:00"`), reads as `GMT`. |
| `Locale` | `string` | A BCP 47 tag: `"zh-CN"`, `"zh-Hans-CN"`, `"en-US-POSIX"`; the root locale as `""` | Refuses nothing: reads `"zh_CN"` as `"zh-CN"`, and any other text; one that is no tag is written back as `"und"`. |
| `Currency` | `string` | Its ISO 4217 code, `"CNY"` | Reads `""` as `null`. Refuses an unknown code and one in lower case, `"cny"`. |
