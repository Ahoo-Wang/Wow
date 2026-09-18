/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * Holds `check-wow-conformance.mjs` to finding every shape Kotlin states a
 * rule with.
 *
 * The script is the only thing that can notice a rule Wow adds upstream, and
 * it reports success by finding nothing — so a gap in how it reads Kotlin
 * looks exactly like conformance. Two earlier drafts were wrong that way: one
 * stopped at the first `)` and saw a third of the rules, the other knew only
 * `require`. The fixture beside this file carries one rule in each shape, and
 * the script has to name all of them.
 */
const script = fileURLToPath(
  new URL('../../scripts/check-wow-conformance.mjs', import.meta.url),
);
const fixture = fileURLToPath(
  new URL('../fixtures/wow-synthetic', import.meta.url),
);

const run = () => {
  const result = spawnSync(process.execPath, [script, fixture], {
    encoding: 'utf8',
  });
  return { ...result, output: `${result.stdout}${result.stderr}` };
};

/** The checker over `wow-ts-comments`, reading `ts-comments` as this package. */
const runOnTsPair = () => {
  const result = spawnSync(
    process.execPath,
    [
      script,
      fileURLToPath(new URL('../fixtures/wow-ts-comments', import.meta.url)),
    ],
    {
      encoding: 'utf8',
      env: {
        ...process.env,
        WOW_CONFORMANCE_TS_SOURCE: fileURLToPath(
          new URL('../fixtures/ts-comments', import.meta.url),
        ),
      },
    },
  );
  return `${result.stdout}${result.stderr}`;
};

describe('check-wow-conformance', () => {
  it.each([
    'Synthetic plain require.',
    'Synthetic require with parentheses in its condition.',
    'Synthetic requireNotNull.',
    'Synthetic check.',
    'Synthetic checkNotNull.',
    'Synthetic error call.',
    'Synthetic throw.',
    'Synthetic paren inside a string.',
    'Synthetic paren inside a char.',
    'Synthetic paren inside a block comment.',
    'Synthetic paren inside a line comment.',
    'Synthetic paren inside a template.',
    // The template holds a string of its own; the message must not end there.
    'Synthetic ${listOf("nested").first()} message.',
  ])('reads a rule stated as: %s', rule => {
    expect(run().output).toContain(rule);
  });

  // Wire values are compared from the Kotlin side, and each shape of drift
  // has a line in `SyntheticEnums.kt` that should produce it.
  it.each([
    [
      'a value Wow gained',
      'AggregationFunction.MEDIAN: Wow has it, this package does not',
    ],
    [
      'a value Wow dropped',
      'SearchMode.PHRASE: this package sends it, Wow does not know it',
    ],
    [
      'a kept-here-only value Wow brought back',
      'Operator.RAW: listed as kept here only, which is no longer true',
    ],
    ['an enum with no counterpart', 'SyntheticOnlyInWow: no counterpart here'],
    [
      'discriminators on a sealed interface',
      'SyntheticDispatch: no counterpart here',
    ],
    [
      'an entry that is not UPPER_SNAKE',
      'Direction.random: Wow has it, this package does not',
    ],
    [
      'a discriminator holding a hyphen, past an annotation with brackets',
      'HavingExpression.not-null: Wow has it, this package does not',
    ],
    // The fixture declares no FilterOperator: to the checker, Wow deleted it.
    [
      'an enum Wow deleted outright',
      'FilterOperator: this package sends it, Wow does not declare it',
    ],
    [
      'an entry whose wire value @JsonProperty sets',
      'AggregationDateUnit.fortnight: Wow has it, this package does not',
    ],
    [
      'a rename that keeps the Kotlin name this package still sends',
      'AggregationDateUnit.MONTH: this package sends it, Wow does not know it',
    ],
    [
      'a constant that starts with a literal but is not one',
      'AggregationGroup: cannot read the discriminator `SyntheticProtocol.Group.JOINED`',
    ],
    [
      'an enum written through @JsonValue',
      'SyntheticWire: serialises through @JsonValue',
    ],
    ['one simple name declared twice', 'SyntheticTwin: declared in both'],
    [
      'a real entry after a nested block comment',
      'ComparisonOperator.BETWEEN_EXCLUSIVE: Wow has it, this package does not',
    ],
    [
      'a subtype named by @JsonTypeName on its class',
      'DerivedExpression.NAMED_BY_ANNOTATION: Wow has it, this package does not',
    ],
    [
      'an entry with something after its name',
      'Trailing: cannot read the entry `BAD extra`',
    ],
    [
      '@JsonValue under the field: use-site target',
      'SyntheticFieldWire: serialises through @JsonValue',
    ],
    [
      '@JsonValue by its qualified name',
      'SyntheticQualifiedWire: serialises through @JsonValue',
    ],
    [
      'subtypes discriminated by class rather than name',
      'ByClass: @JsonTypeInfo uses CLASS, so its subtype names are not its wire discriminators',
    ],
    [
      'subtypes with no @JsonTypeInfo of their own',
      'Untyped: has no @JsonTypeInfo of its own',
    ],
    [
      'a subtype named by nothing it can read',
      'DerivedExpression: cannot read the discriminator of `Type(DerivedExpression.Unnamed::class)`',
    ],
    [
      'a discriminator spelled as a constant',
      'AggregationGroup.new-type: Wow has it, this package does not',
    ],
    [
      'a discriminator whose constant cannot be found',
      'AggregationGroup: cannot read the discriminator `Nowhere.MISSING`',
    ],
    [
      'an entry the parser cannot read',
      'Unreadable: cannot read the entry `@ BROKEN`',
    ],
    [
      'a constant that goes on past a line break',
      'AggregationGroup: cannot read the discriminator `SyntheticProtocol.Group.DOTTED`',
    ],
    [
      'an annotation argument that goes on past a line break',
      'AggregationGroup: cannot read the discriminator `"TERMS" + "-wrapped"`',
    ],
    [
      'a constant that only starts the expression',
      'AggregationGroup: cannot read the discriminator `SyntheticProtocol.Group.NEW_TYPE + "-v2"`',
    ],
    [
      'a constant declared under one path in two packages',
      'AggregationGroup: cannot read the discriminator `SyntheticProtocol.Group.SHARED`',
    ],
    [
      'annotations by their qualified names',
      'SyntheticQualifiedDispatch: no counterpart here',
    ],
    [
      '@JsonTypeName by its qualified name',
      'DerivedExpression.QUALIFIED_BY_ANNOTATION: Wow has it, this package does not',
    ],
    [
      'an annotation imported under another name',
      'SyntheticAlias.kt: imports JsonSubTypes as Subtypes',
    ],
    [
      'an id kept in a comment above the one in use',
      'CommentedId: @JsonTypeInfo uses CLASS',
    ],
    [
      'the class a subtype names, not one its comment mentions',
      'DerivedExpression.RENAMED_BY_ANNOTATION: Wow has it, this package does not',
    ],
  ])('reports %s', (_shape, report) => {
    expect(run().output).toContain(report);
  });

  it('keeps a nested block comment whole', () => {
    // Read as code, its tail would yield these as entries.
    expect(run().output).not.toContain('ComparisonOperator.NOT_EQ');
    expect(run().output).not.toContain('ComparisonOperator.still');
  });

  it('reads a subtype with a comment before it', () => {
    // A comment between entries is ordinary Kotlin. Read as the start of the
    // entry, it made a well-formed subtype an unreadable one.
    expect(run().output).not.toContain('cannot read the subtype');
  });

  it('reads entries that carry arguments and a body', () => {
    expect(run().output).not.toContain('SyntheticOnlyInWow: cannot read');
  });

  it('does not count a commented-out member as one this package sends', () => {
    // PHRASE survives in the TypeScript only in comments, so it is missing
    // here; read from the comments, it would match Wow and pass.
    const output = runOnTsPair();
    expect(output).toContain(
      'SearchMode.PHRASE: Wow has it, this package does not',
    );
    // And a member with a doc comment of its own still reads: flagging every
    // documented member as unreadable would fail on this package's own source.
    expect(output).not.toContain("cannot read this package's member");
  });

  it('reads a member whose value holds a comma or a brace', () => {
    // Both sides carry `a,b` and `a}b`. Split at every comma, or cut at the
    // first brace, this package's members would read as fragments, and the
    // enum would be reported however well it matched.
    expect(runOnTsPair()).not.toContain('SyntheticPunctuated');
  });

  it('does not take a declaration in a comment or a string for a real one', () => {
    // The checkout holds these only in a KDoc example and a string, so Wow
    // declares none of them; were they found, they would stand in for real
    // declarations and the reverse comparison would pass.
    const output = spawnSync(
      process.execPath,
      [script, fileURLToPath(new URL('../fixtures/wow-kdoc', import.meta.url))],
      { encoding: 'utf8' },
    );
    const text = `${output.stdout}${output.stderr}`;
    expect(text).toContain(
      'SearchMode: this package sends it, Wow does not declare it',
    );
    expect(text).toContain(
      'AggregationGroupType: this package sends it, Wow does not declare it',
    );
    expect(text).not.toContain('Commented rule.');
    // Found in the comment, the example would also raise a false alarm about
    // an owner it cannot name — a checker that cries wolf on documentation is
    // one people learn to ignore.
    expect(text).not.toContain('an unnamed @JsonSubTypes');
  });

  it('still reads an entry past its annotation', () => {
    // Were QUARTER dropped, it would be reported as sent here and unknown to Wow.
    expect(run().output).not.toContain(
      'AggregationDateUnit.QUARTER: this package sends it',
    );
  });

  it('exits non-zero when the register does not name a rule', () => {
    // None of the fixture's rules is in the register, so every one of them is
    // reported and the run fails. That is the whole contract.
    expect(run().status).toBe(1);
  });

  it('refuses a directory that is not a Wow checkout', () => {
    const result = spawnSync(process.execPath, [script, fixture + '/wow-api'], {
      encoding: 'utf8',
    });
    expect(result.status).not.toBe(0);
    expect(`${result.stdout}${result.stderr}`).toContain('Not a Wow checkout');
  });
});
