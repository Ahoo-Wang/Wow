/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  annotationOf,
  configurationGroups,
  firstSentence,
  frameworkErrors,
  plain,
  queryErrorCodes,
  referencePages,
  staleMessage,
  manifestObjects,
  markedDeclarations,
  publishedModules,
} from './docs-reference.mjs';

test('prose loses the design records’ references, and a KDoc link reads as code outside code spans', () => {
  assert.equal(
    plain(
      'The backend (decision D110): `auto` takes [AggregateRoot] (review R3.5-1) or `wow.x.[a.b]` .',
    ),
    'The backend: `auto` takes `AggregateRoot` or `wow.x.[a.b]`.',
  );
  assert.equal(
    plain('An event handler (V10, decision D67): it takes one.'),
    'An event handler: it takes one.',
  );
  assert.equal(
    plain('Keep (the default) as it is.'),
    'Keep (the default) as it is.',
  );
});

test('the first sentence ends at a full stop before the next sentence', () => {
  assert.equal(firstSentence('A hook. It runs `a.b` later.'), 'A hook.');
  assert.equal(
    firstSentence('Calls `a.b()` and stops'),
    'Calls `a.b()` and stops',
  );
});

test('a property belongs to the group of its source type, else to the longest group above it', () => {
  const groups = configurationGroups({
    groups: [
      { name: 'wow', type: 'W' },
      { name: 'wow.calls', type: 'W$Calls', description: 'Calls.' },
      { name: 'wow.empty', type: 'W$Empty' },
    ],
    properties: [
      { name: 'wow.calls.timeout', sourceType: 'W$Calls' },
      { name: 'wow.backend', sourceType: 'W' },
      { name: 'wow.calls.contexts', sourceType: 'Other' },
      { name: 'wow.aggregates', sourceType: 'W' },
    ],
  });
  assert.deepEqual(
    groups.map(({ name, properties }) => [
      name,
      properties.map(property => property.name),
    ]),
    [
      ['wow', ['wow.aggregates', 'wow.backend']],
      ['wow.calls', ['wow.calls.contexts', 'wow.calls.timeout']],
    ],
  );
});

test('the framework codes are read from the ALL table, concatenated descriptions included', () => {
  const source = `object FrameworkErrors {
    const val A: String = "wow.a"
    const val B: String = "wow.b"
    val ALL: List<ErrorManifest> = listOf(
        ErrorManifest(A, 401, "Short."),
        ErrorManifest(
            B,
            503,
            "Long " +
                "and \\"quoted\\".",
        ),
    )
}`;
  assert.deepEqual(frameworkErrors(source), [
    { code: 'wow.a', status: 401, description: 'Short.' },
    { code: 'wow.b', status: 503, description: 'Long and "quoted".' },
  ]);
});

test('the query codes take the first sentence of their group’s comment', () => {
  const source = `object QueryErrorCodes {
    // Reading the body; BindingError.name is the path.
    const val INVALID_JSON = "INVALID_JSON"
    const val EMPTY_BODY = "EMPTY_BODY"

    // Admission against the model. More.
    const val UNKNOWN_FIELD = "UNKNOWN_FIELD"
}`;
  assert.deepEqual(queryErrorCodes(source), [
    { code: 'INVALID_JSON', group: 'Reading the body.' },
    { code: 'EMPTY_BODY', group: 'Reading the body.' },
    { code: 'UNKNOWN_FIELD', group: 'Admission against the model.' },
  ]);
});

test('an annotation gives its summary, targets and parameters, multi-line defaults included', () => {
  const annotation = annotationOf(`package a

/**
 * An aggregate root: the compiler binds it.
 *
 * \`\`\`kotlin
 * @AggregateRoot
 * \`\`\`
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@MustBeDocumented
annotation class AggregateRoot(
    // The lifecycle commands.
    val lifecycle: Array<KClass<*>> = [
        DeleteAggregate::class,
        RestoreAggregate::class,
    ],
    val topic: String = "",
    val retry: Map<String, Int> = emptyMap(),
)
`);
  assert.deepEqual(annotation, {
    name: 'AggregateRoot',
    summary: 'An aggregate root: the compiler binds it.',
    targets: ['CLASS', 'ANNOTATION_CLASS'],
    parameters: [
      {
        name: 'lifecycle',
        type: 'Array<KClass<*>>',
        default: '[DeleteAggregate::class, RestoreAggregate::class]',
      },
      { name: 'topic', type: 'String', default: '""' },
      { name: 'retry', type: 'Map<String, Int>', default: 'emptyMap()' },
    ],
  });
  assert.deepEqual(
    annotationOf('/** A marker. */\nannotation class Blocking\n').parameters,
    [],
  );
  assert.equal(annotationOf('class NotAnAnnotation'), undefined);
});

test('the committed reference pages are what their sources give', () => {
  for (const [path, text] of Object.entries(referencePages())) {
    assert.equal(
      readFileSync(new URL(`../../${path}`, import.meta.url), 'utf8'),
      text,
      `${path}: run pnpm docs:reference`,
    );
  }
});

test('a failed check names the pages, their sources and the command that fixes them', () => {
  assert.equal(
    staleMessage([
      'documentation/docs/en/reference/error-codes.md',
      'documentation/docs/zh/reference/error-codes.md',
    ]),
    [
      'The generated reference pages are not what their sources give:',
      '  documentation/docs/en/reference/error-codes.md',
      '  documentation/docs/zh/reference/error-codes.md',
      'They are generated from: api/manifest/src/main/kotlin/me/ahoo/wow/manifest/FrameworkErrors.kt, api/manifest/src/test/resources/framework-errors.json, api/query-api/src/main/kotlin/me/ahoo/wow/api/query/QueryErrorCodes.kt.',
      'Fix: run `pnpm docs:reference` (or `node .github/scripts/docs-reference.mjs --write`) and commit those pages',
      'with the change to their sources.',
    ].join('\n'),
  );
});

test('the published modules come from the build, in its order, with their directories and descriptions', () => {
  const files = {
    'build.gradle.kts': `val abiProjects = includedProjects(
    ":api", ":test-dsl",
)
val publishedLibraries = abiProjects + includedProjects(":migrate-data")
val artifactIds = mapOf("test-dsl" to "wow-test")`,
    'settings.gradle.kts': `project(":api").projectDir = file("api/api")
project(":test-dsl").projectDir = file("test/test-dsl")
project(":migrate-data").projectDir = file("migration/migrate-data")`,
    'api/api/build.gradle.kts':
      'description = "Wow API: what domain code imports, " +\n    "and the wire types"\n',
    'test/test-dsl/build.gradle.kts':
      'description = "Wow Test: the test DSL"\n',
    'migration/migrate-data/build.gradle.kts':
      '// A tool.\ndescription = "Wow data migration: imports 9.x"\n',
  };
  assert.deepEqual(
    publishedModules(path => files[path]),
    [
      {
        project: 'api',
        artifact: 'wow-api',
        dir: 'api/api',
        description: 'Wow API: what domain code imports, and the wire types',
      },
      {
        project: 'test-dsl',
        artifact: 'wow-test',
        dir: 'test/test-dsl',
        description: 'Wow Test: the test DSL',
      },
      {
        project: 'migrate-data',
        artifact: 'wow-migrate-data',
        dir: 'migration/migrate-data',
        description: 'Wow data migration: imports 9.x',
      },
    ],
  );
  assert.throws(
    () =>
      publishedModules(path =>
        path === 'settings.gradle.kts' ? '' : files[path],
      ),
    /no directory for :api/,
  );
});

test('the marked declarations are the public ones, members by their type, fun interfaces as interfaces', () => {
  const source = `package me.ahoo.wow.q

@WowSpi
interface Backend {
    @WowSpi
    fun run(): Int

    @OptIn(WowSpi::class)
    fun other(): Int
}

/** A port. */
@WowSpi
fun interface Listener {
    fun on()
}

@WowSpi
internal class Hidden {
    @WowSpi
    fun inner() = Unit
}

@WowSpi
inline fun <reified R : Any> Backend.rows(): R = TODO()

class Plain {
    @WowSpi
    override fun toString() = ""

    @WowSpi
    val size: Int = 0
}
`;
  assert.deepEqual(markedDeclarations(source, 'WowSpi'), [
    { name: 'me.ahoo.wow.q.Backend', kind: 'interface' },
    { name: 'me.ahoo.wow.q.Backend.run', kind: 'fun' },
    { name: 'me.ahoo.wow.q.Listener', kind: 'interface' },
    { name: 'me.ahoo.wow.q.rows', kind: 'fun' },
    { name: 'me.ahoo.wow.q.Plain.size', kind: 'val' },
  ]);
});

test('the Manifest outline follows the writer: nested objects, arrays of written types, optional keys', () => {
  const source = `object ManifestTree {
    private const val DEFS = "\\$defs"

    fun write(manifest: Manifest): Map<String, Any?> = obj(
        "format" to manifest.format,
        "service" to manifest.service?.let { obj("name" to it.name, "version" to it.version) },
        "contexts" to manifest.contexts.map(::writeContext),
        DEFS to manifest.defs,
    )

    private fun writeContext(context: ContextManifest): Map<String, Any?> = obj(
        "name" to context.name,
        "published" to context.published.map {
            obj("aggregate" to it.aggregate, "includeState" to it.includeState)
        },
        // Written only when raised ("quoted" in a comment).
        "revision" to context.revision.takeIf { it != 1 },
    )

    /** A JSON object of the non-null [entries], in their order. */
    private fun obj(vararg entries: Pair<String, Any?>): Map<String, Any?> = TODO()
}`;
  assert.deepEqual(manifestObjects(source), [
    {
      name: 'Manifest',
      entries: [
        { key: 'format', kind: 'value', optional: false },
        {
          key: 'service',
          kind: 'object',
          optional: true,
          entries: [
            { key: 'name', kind: 'value', optional: false },
            { key: 'version', kind: 'value', optional: false },
          ],
        },
        { key: 'contexts', kind: 'array', ref: 'Context', optional: false },
        { key: '$defs', kind: 'value', optional: false },
      ],
    },
    {
      name: 'Context',
      entries: [
        { key: 'name', kind: 'value', optional: false },
        {
          key: 'published',
          kind: 'objects',
          optional: false,
          entries: [
            { key: 'aggregate', kind: 'value', optional: false },
            { key: 'includeState', kind: 'value', optional: false },
          ],
        },
        { key: 'revision', kind: 'value', optional: true },
      ],
    },
  ]);
});
