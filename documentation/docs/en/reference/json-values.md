---
title: JSON Values
description: How Wow's JSON writes each platform value class a state, a command or an event holds, and the schema type and format the Manifest gives it.
---

# JSON Values

Wow's JSON (`WowJson`) writes and reads the states, commands and events of a service: in the event store, in snapshots, on Kafka and over HTTP. This page lists each platform value class it writes as a scalar, with the JSON type and format the Manifest's schema gives the class and the text Wow's JSON writes for it.

- The classes are the JVM's: a Kotlin `Int` is `int`, or `Integer` where it is nullable or a type argument, and a `ByteArray` is `byte[]`. A row matches its class itself, not a subclass.
- A JavaScript `number` holds an integer exactly only up to 2^53 − 1 (`9007199254740991`): a client reads a larger `long` as a `bigint`.
- An enumeration is written by its name, as its schema's `enum` lists it: a `Month` as `"JANUARY"`.
- A property holding another platform class written as a scalar (a `Charset`, a `Path`, an `AtomicLong`) stops the build, unless its schema is declared ([Manifest](../guide/manifest#a-type-written-by-a-custom-serializer)).
- A test holds each row to the code: its classes, JSON type and format to what the Manifest describes, and each value it shows to what Wow's JSON writes.

The written form is what Wow promises, and what a client that is not Wow writes. Wow's JSON reads some other forms as well, by its JSON library's defaults: those are not promised, and are being decided ([#6488](https://github.com/Ahoo-Wang/Wow/issues/6488)). Two refusals are decided, and tests hold them:

- A `kotlin.time.Duration` refuses a number: it is ISO-8601 text.
- A `Month` reads its name or its one-based number, and refuses `0`.

| Class | JSON | Written |
| --- | --- | --- |
| `boolean`, `Boolean` | `boolean` | `true`, `false` |
| `int`, `Integer` | `integer`, `int32` | `1`, `2147483647`, `-2147483648` |
| `short`, `Short` | `integer`, `int32` | `1`, `-32768`, `32767` |
| `byte`, `Byte` | `integer`, `int32` | `1`, `-128`, `127` |
| `Year` | `integer`, `int32` | `2026`, `0`, `-1`, `12026` |
| `long`, `Long` | `integer`, `int64` | `1`, `9223372036854775807` |
| `BigInteger` | `integer`, `bigint` | `123456789012345678901234567890`, of any length |
| `float`, `Float` | `number`, `float` | `1.5`, `0.1`, `3.4028235E38`, `-0.0`; not-a-number and the infinities as strings, `"NaN"`, `"Infinity"`, `"-Infinity"` |
| `double`, `Double` | `number` | `0.1`, `1.0`, `1.0E300`, `-0.0`; not-a-number and the infinities as strings, `"NaN"`, `"Infinity"`, `"-Infinity"` |
| `BigDecimal` | `number`, `decimal` | Its digits and scale, a trailing zero kept, `2.50`, `0.000001`; a negative scale or a very small value in exponent form, `1E+3`, `1E-7` |
| `Instant` | `string`, `date-time` | At UTC, `"2026-01-02T03:04:05Z"`; its fraction to the nanosecond as needed, `"2026-01-02T03:04:05.123456789Z"`; a year past 9999 signed, `"+12026-01-01T00:00:00Z"`, and one before 1, `"-0001-01-01T00:00:00Z"` |
| `OffsetDateTime` | `string`, `date-time` | Its offset kept, `"2026-01-02T03:04:05+08:00"` |
| `ZonedDateTime` | `string`, `date-time` | Its offset, not its zone: Asia/Shanghai's 03:04:05 on 2 January 2026 is `"2026-01-02T03:04:05+08:00"` |
| `Date` | `string`, `date-time` | At UTC, the milliseconds always written, `"2026-01-02T03:04:05.123Z"` |
| `Calendar` | `string`, `date-time` | As a Date is, at UTC: its time zone is not written, `"2026-01-02T03:04:05.123Z"` |
| `LocalDate` | `string`, `date` | `"2026-01-02"`; a year past 9999 signed, `"+12026-01-01"`; a negative one, `"-0001-01-01"` |
| `LocalTime` | `string`, `time` | The seconds always written, `"03:04:05"`, `"03:04:00"`; the fraction as needed, `"03:04:05.12"` |
| `OffsetTime` | `string`, `time` | `"03:04:05+08:00"`; zero seconds left out, `"03:04Z"` |
| `Duration` | `string`, `duration` | ISO-8601, `"PT1H30M"`, `"PT0S"`; days as hours, `"PT48H"`; a negative one, `"PT-1.5S"`; to the nanosecond, `"PT0.000000001S"` |
| `Period` | `string`, `duration` | ISO-8601, `"P1Y2M3D"`, `"P0D"`; weeks as days, `"P14D"`; a negative one, `"P-1M"` |
| `kotlin.time.Duration` | `string`, `duration` | As a Duration is, `"PT1H30M"`; a negative one, `"PT-1H-30M"`; the infinite one, `"PT2562047788015215H30M7S"` |
| `UUID` | `string`, `uuid` | Lower case, 36 characters, `"00000000-0000-0001-0000-000000000002"` |
| `URI` | `string`, `uri` | As it is, encoded, `"https://example.com/a%20b"`, `"urn:isbn:123"`, `"/relative"` |
| `URL` | `string`, `uri` | `"https://example.com/a"` |
| `byte[]` | `string`, `byte` | Base64 with padding, `"AQID"`, `"//79/A=="`; empty, `""` |
| `String` | `string` | As it is, escaped as JSON escapes it, `"\u0000"` |
| `char`, `Character` | `string` | One character, `"c"` |
| `LocalDateTime` | `string` | The seconds always written, `"2026-01-02T03:04:05"`; a year past 9999 signed, `"+12026-01-01T00:00:00"` |
| `YearMonth` | `string` | `"2026-01"`, `"0005-01"`; a year past 9999 with no sign, `"12026-01"`; a negative one, `"-0001-01"` |
| `MonthDay` | `string` | `"--01-02"`, `"--02-29"` |
| `ZoneId` | `string` | `"Asia/Shanghai"`, `"UTC"`, `"Z"`, `"GMT+08:00"` |
| `ZoneOffset` | `string` | `"+08:00"`, `"+05:30"`, `"-18:00"`; zero as `"Z"`; seconds as needed, `"+01:02:03"` |
| `TimeZone` | `string` | Its id, `"UTC"`, `"Asia/Shanghai"`, `"GMT+08:00"` |
| `Locale` | `string` | A BCP 47 tag, `"zh-CN"`, `"zh-Hans-CN"`, `"en-US-POSIX"`; the root locale as `""` |
| `Currency` | `string` | Its ISO 4217 code, `"CNY"` |
