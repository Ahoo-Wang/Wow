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
