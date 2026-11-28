---
title: The Manifest and Compatibility
description: How a service's Manifest is built and merged, what it is used for, how peers get their part of it, and how the compatibility check keeps a change from breaking clients.
outline: deep
---

# The Manifest and Compatibility

A service's Manifest is its contract in one document: its contexts, aggregates, commands, events, state schemas, subscriptions, published streams and error codes. The routes, the OpenAPI document, the generated clients, wait plans and query admission are all derived from it. This page follows it from the compiler to the service, to its peers and to the compatibility check. [One Contract: the Manifest](../concepts/contract) explains why it exists.

## From fragments to the service's Manifest

1. **The compiler** writes each domain module's fragment, `META-INF/wow/manifest/<module>.json` ([What the Compiler Generates](./generated-code#the-manifest-fragment)).
2. **The Gradle plugin** (`generateWowManifestFragment`) completes the fragment with the JSON Schemas of its types, the query keywords and the state's fingerprint. A type's Jakarta Bean Validation constraints become its schema's validation keywords, so a client sees them before it sends: `@Positive` is an `exclusiveMinimum` of 0, `@NotBlank` a `minLength` of 1, `@Size` the length or item bounds, `@Pattern` a `pattern` and `@Email` the `email` format. Whether a field is required, or may be null, still follows its Kotlin type. The completed fragment replaces the compiler's in the module's resources. The task loads the module's compiled classes, so it runs on the module's Java toolchain (`kotlin { jvmToolchain(25) }`), not on the JVM that runs Gradle: Gradle may be older than the module.
3. **At build time**, `mergeWowManifest` merges the fragments of the service's runtime class path into `build/wow/manifest.json`.
4. **At startup**, the host merges the same fragments, adds the framework's error codes, and serves the result at `GET /wow/manifest`: on the internal port to the cluster's services, and on the public port only with `wow.http.manifest.enabled: true` (off by default, as the Manifest lists every route, schema and error of the service), authenticated like every route there: a request with no caller is answered 401.

The snapshot queries read the state schema from the completed fragment: a state field can be filtered and sorted only once the plugin has completed it.

## A type written by a custom serializer

The plugin learns a type's schema by asking Jackson how it writes the class. It cannot see inside a serializer of the class's own (`@JsonSerialize(using = …)`) or a `@JsonValue`, so it does not guess: `generateWowManifestFragment` stops and asks for the shape. It stops too for a class of any kind, an interface, an abstract class or an enum among them, that `@JsonSerialize` gives a serializer or a converter of its own, its own annotation or a supertype's, whatever the serializer writes.

```text
Jackson does not write com.example.ledger.Ledger as an object (a custom serializer or @JsonValue), so its schema cannot be introspected: declare it in wow { schemaOverrides.put("com.example.ledger.Ledger", "<JSON Schema>") }.
```

Declare the shape the serializer writes, as a JSON Schema, in the domain module's `build.gradle.kts`. Each class is named by its binary name, so a nested class is `Outer$Inner`. A state type and an event are declared alike:

<!-- sample: fragment -->

```kotlin
wow {
    // The state, which LedgerSerializer writes as {"owner": "…", "lines": [12, 30]}
    schemaOverrides.put(
        "com.example.ledger.Ledger",
        """{"type":"object","properties":{"owner":{"type":"string"},"lines":{"type":"array","items":{"type":"integer","format":"int64"}}},"required":["owner","lines"]}""",
    )
    // An event, which PostedSerializer writes as {"amount": 250}
    schemaOverrides.put(
        "com.example.ledger.Posted",
        """{"type":"object","properties":{"amount":{"type":"integer","format":"int64"}},"required":["amount"]}""",
    )
}
```

A property written by a serializer of its own, `@JsonSerialize` on the property with `using`, `converter`, `contentUsing` or `contentConverter`, or `@JsonRawValue`, stops the build the same way. The commonest case is a `Long` id written as a string, which the Manifest and the generated client would otherwise call a number:

```text
Jackson writes com.example.ledger.Order#id with a serializer of its own (@JsonSerialize using, converter, contentUsing or contentConverter, or @JsonRawValue), so its schema cannot be introspected: declare it in wow { schemaOverrides.put("com.example.ledger.Order#id", "<JSON Schema>") }.
```

<!-- sample: fragment -->

```kotlin
wow {
    // Order's id, written by @field:JsonSerialize(using = ToStringSerializer::class) as "1024"
    schemaOverrides.put("com.example.ledger.Order#id", """{"type":"string"}""")
}
```

A property holding a platform class that Wow's JSON writes as a string or a number stops the build too, unless the Manifest describes that class as written. Those it describes are the value classes a state, a command or an event commonly holds: `Year` as an integer, `YearMonth`, `MonthDay`, `ZoneOffset`, `TimeZone`, `Locale`, `Currency` and `URL` as strings, a `ByteArray` as its Base64 text, and a `kotlin.time.Duration` as its ISO-8601 text (`PT1H30M`) as a `java.time.Duration` is, beside the dates, times, durations, numbers and UUIDs. [JSON Values](../reference/json-values) lists each, with the text written for it and what its reader refuses. A `Month` is an enumeration written by its name, as every enumeration is. Any other such class is refused, held directly or in a collection, an `Optional` or a map's values: a `Charset`, a `Path`, an `AtomicLong`, and a value class over a `kotlin.time.Duration`, which the Kotlin module writes as the duration's internal number. Declare the property's schema, or hold the value as a `String`:

```text
Wow's JSON does not write java.nio.charset.Charset, which com.example.ledger.Ledger#encoding holds, as an object, and it is no scalar Wow describes, so its schema would not be what is written: declare it in wow { schemaOverrides.put("com.example.ledger.Ledger#encoding", "<JSON Schema>") }, or hold it as a String.
```

A Jackson tree needs no declaration. Wow's JSON writes it as the JSON it holds, so the Manifest describes a `tools.jackson.databind` `ObjectNode` as an object (`{"type":"object"}`), an `ArrayNode` as an array, and any other `JsonNode` as any value (`{}`), named by no class: the free shape a state, a command or an event holds content of no class in, such as a 9.x shape with no V10 type. A declared schema of one wins, as of any class. A Jackson 2 tree (`com.fasterxml.jackson.databind.JsonNode`) is refused, declared or not: Wow's JSON is Jackson 3's, which writes it as a bean of its getters (`"nodeType"`, `"object"`, …), not as the JSON it holds, so hold a Jackson 3 one.

A key names a class by its binary name, or a property by the class that declares the accessor Jackson writes it by, `#`, and the property's name as that class declares it, whatever its JSON name. The stop prints the exact line. So an inherited property is named once, by the class that declares it, a property a class overrides by that class, and a property an interface's annotated getter declares by each class that implements it. A key's declared schema stands for the whole class, or the whole property as written on the wire, whenever the key is present: a property's list items and map values included. The generator adds to a declared property only what it adds to any property: `null` to the types of one that can be null, and the keywords of its query annotations. A property's key wins over its type's, and a key that names nothing in the module is ignored, so a convention plugin may share one `wow { }` block. A key serializer (`keyUsing`) needs no declaration: a JSON object's keys are strings, which the schema does not constrain.

The declared schema stands for the class wherever the Manifest is read:
- the snapshot and event queries filter, sort and project its fields, such as `state.owner`;
- OpenAPI and the generated clients describe it;
- the [compatibility check](#the-compatibility-check) compares it with the baseline, under the rules of any state or event schema.

The check compares the declared shape, not what the serializer writes. The two are kept in step by hand, and nothing checks one against the other. If the serializer starts writing another field and its declaration does not change, the wire changes and the check does not see it. Change both in the same commit.

## Peers get their part

A service that sends another context's commands, or reads its published stream, needs that context's commands, events, state and errors. The plugin selects them from the other context's fragment, as `META-INF/wow/contract.json`, which its api module publishes as its feature `contract`. A service that depends on the api module finds the contract on its class path, and uses it to name the commands it sends, check the wait plans that cross into the other context, and decode its published events. Nothing is configured: depending on the api is enough.

A tool that cannot take the api jar, such as a console or a gateway, reads the peer's Manifest from its `GET /wow/manifest` instead ([Call Other Services](./calling-services#call-a-command-by-name)): from the internal port as a service, or from the public port of a peer that serves it there (`wow.http.manifest.enabled: true`).

## The compatibility check

Each service commits its merged Manifest as a baseline, `manifest/baseline.json`. The baseline records the service's compatibility line, the major of its version (`"line": 10`), not the version, which moves with every release. `checkWowManifest`, part of `check`, compares every build's merged Manifest with it and sorts each difference:

| Kind | Examples | In `check` |
| --- | --- | --- |
| Additive | A new command, event or aggregate; a new optional field; a new computed property; a command's constraint loosened | Fails until the baseline records it |
| Breaking | A removed command or event; a new required field; a field removed or retyped; a command's constraint tightened, such as a minimum raised or a `pattern` added; an event's or a state's field removed, retyped or made required, whatever its revision; a command's aggregate id moved to another field of its body, or out of it (`idProperty`) | Fails the build within the line, listing each change and its path |

The constraint rows are a command's, which its clients send: a tighter constraint refuses values they sent before. A state's or an event's schema is what its readers read: the events query's clients, whom every aggregate has, the snapshot query's and the published stream's subscribers. So for a state or an event the constraint rows turn around: a constraint tightened there is additive, and one loosened breaks; the service's own stored records are never checked against a constraint. A field removed, retyped or made required breaks those readers whatever the revision: a new revision and its `@Upgrade` keep the service's own history readable, not its readers. To change an event within the line, add a new event beside it and retire the old one once its readers have moved, or wait for the next major.

A computed property, a getter with no field behind it, is `readOnly` in the schema and listed as required unless its type is nullable, since Wow's JSON always writes it. Nothing reads it back: a command's body may leave it out or carry a value the service ignores, and a stored snapshot or event reads without it. So the check requires it of no reader, only of what the service writes: a new computed property is additive, on a command, on a value type a command holds, or on a state. Snapshots written before it do not hold it, so a query naming it misses them until they are rewritten, by the aggregate's next commit or a snapshot rebuild. A computed property turned into a required field breaks, since old clients and old records never had to hold it, and one removed is a field removed: a client that hands back a value it read is refused (`unknown-property`).

A number's width is part of its type. A command field narrowed from `Long` to `Int` (a `format` of `int64` to `int32`, or `Double` to `Float`) refuses numbers its clients sent, and breaks; widened from `Int` to `Long`, it is additive. For a state or an event it turns around again: widening breaks, since a reader generated as an `Int` cannot hold a `Long`, while the service's own stored records must still read back, so narrowing them breaks too. A hand-written schema's `oneOf` takes a value that matches exactly one alternative, so a new or widened alternative that shares a JSON type with another (an `integer` is a `number`) breaks a command: a value its clients sent may now match two. A discriminated `oneOf` may still grow: an alternative whose required property, its discriminator, holds a `const` (or `enum`) no other alternative's values share is additive, since no value can match two. `anyOf` has no such rule.

`build/wow/manifest-compatibility.txt` lists every change, additive ones too. A missing baseline fails `check` too. After an intended change, `updateWowManifestBaseline` records the new baseline: commit it with the change, so its review shows what clients and peers will see. Each failure names that task. The eShop regenerates its TypeScript clients from the baselines, so a client and its service never drift apart unnoticed.

A breaking change needs the service's next major version, or a new command or event beside the old one, the old one retired once its last client has moved. While the service's major equals the baseline's line, `checkWowManifest` fails a break and `updateWowManifestBaseline` refuses to record it. Once the version's major is greater than the line, the update records the break and the new line in the same change. A pre-release of a major's first version, such as `11.0.0-beta.1`, keeps its line open: it records a break, since nothing has shipped on that line yet. Any other pre-release records a break only with `-Pwow.manifest.allowBreaking`, and the update's report, `build/wow/manifest-baseline.txt`, logs the flag and the changes it let through.

## What reads the Manifest

| Reader | Uses it for |
| --- | --- |
| The host | Its routes, wait plans, query admission (ownership, schemas), the internal port's call permissions |
| The OpenAPI document | Every route, its request and response schemas ([OpenAPI and springdoc](./openapi)) |
| The TypeScript generator | Typed command and query clients |
| The view engine | The fields and capabilities a view may use |
| Peers | Their contract with this context, through its api module's contract |
| The ops console and migrate-data | Commands, subscriptions and types by name |

A Manifest that changes what a part of it means lists a marker in `requires`: on the Manifest for the whole of it, or on a context or an aggregate for that part alone. A reader that does not understand a marker refuses the whole Manifest rather than misread it, wherever the marker stands, and says where: the level, the node and the marker, such as `The aggregate shop.cart requires x`. The JVM's reader does so (the host, the build, a peer's contract on the class path), and so do `@ahoo-wang/wow-client`'s `checkManifest`, which its `fetchManifest` and a generated client's routing part go through, and the TypeScript generator. A peer's contract and a generated client's routing part keep the markers of what they keep, where they stand. The compatibility check takes a marker added, at any level and on a new context or aggregate too, for a break, since every reader that does not know it refuses the whole Manifest; a marker removed is additive. A reader from before markers could stand on a context or an aggregate ignores them there. Format 1 defines none yet.
