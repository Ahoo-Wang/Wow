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

/**
 * The markers of `requires`: the generator refuses, before anything else, a
 * Manifest that requires one it does not understand wherever it stands, as
 * the table every Manifest reader holds to says; and the routing part a
 * generated client carries keeps the markers of what it keeps, where they
 * stand, sorted and distinct.
 */

import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { IndentationText, Project, QuoteKind } from 'ts-morph';
import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import { ModuleSet } from '../../src/emit/moduleBuilder';
import { emitRouting } from '../../src/emitters/manifest/routing';
import { analyzeManifest } from '../../src/manifest/analyze';
import {
  type ManifestDocument,
  validateManifest,
} from '../../src/manifest/document';

/** wow-client's table, which the Kotlin reader's and the client's tests hold their readers to too. */
const CASES = join(
  import.meta.dirname,
  '../../../wow-client/fixtures/manifest-requires-cases.json',
);

const { cases } = JSON.parse(readFileSync(CASES, 'utf8')) as {
  cases: readonly {
    readonly name: string;
    readonly manifest: unknown;
    readonly refused: string;
  }[];
};

describe('a marker the generator does not understand', () => {
  it.each(cases.map(entry => [entry.name, entry] as const))(
    'is refused on %s, naming where it stands',
    (_, entry) => {
      let failure: unknown;
      try {
        validateManifest(entry.manifest, 'm.json');
      } catch (error) {
        failure = error;
      }
      expect(failure).toBeInstanceOf(GeneratorError);
      expect((failure as GeneratorError).kind).toBe('input');
      expect((failure as Error).message).toContain(
        `m.json: the ${entry.refused}, which this wow-generator does not understand: generate with a newer @ahoo-wang/wow-generator.`,
      );
    },
  );
});

/** A Manifest of the context `probe` and its aggregate `thing`, the markers at each level as given. */
function probe(requires: {
  manifest?: string[];
  context?: string[];
  aggregate?: string[];
}): ManifestDocument {
  return {
    format: 1,
    ...(requires.manifest ? { requires: requires.manifest } : {}),
    contexts: [
      {
        name: 'probe',
        ...(requires.context ? { requires: requires.context } : {}),
        aggregates: [
          {
            name: 'thing',
            ...(requires.aggregate ? { requires: requires.aggregate } : {}),
            state: { schema: '#/$defs/a.State' },
            commands: [
              {
                name: 'Do',
                schema: '#/$defs/a.Body',
                routes: [{ method: 'POST', path: '/probe/thing/{id}/do' }],
              },
            ],
          },
        ],
      },
    ],
    $defs: {
      'a.State': { type: 'object', properties: {} },
      'a.Body': { type: 'object', properties: {} },
    },
  } as unknown as ManifestDocument;
}

/** The routing part `emitRouting` writes of the one context of `manifest`. */
function routing(manifest: ManifestDocument): string {
  const files = new Project({ useInMemoryFileSystem: true });
  files.manipulationSettings.set({
    indentationText: IndentationText.TwoSpaces,
    quoteKind: QuoteKind.Single,
    useTrailingCommas: true,
  });
  const modules = new ModuleSet(
    path =>
      files.getSourceFile(`/out/${path}`) ??
      files.createSourceFile(`/out/${path}`, ''),
  );
  const { model } = analyzeManifest(manifest);
  emitRouting(model.contexts[0]!, modules, model.requires);
  modules.build();
  return files.getSourceFileOrThrow('/out/probe/manifest.ts').getFullText();
}

describe('the routing part a generated client carries', () => {
  it('keeps the markers of what it keeps where they stand, sorted and distinct', () => {
    const text = routing(
      probe({
        manifest: ['b', 'a', 'b'],
        context: ['c'],
        aggregate: ['e', 'd'],
      }),
    );
    expect(text).toContain(
      "  format: MANIFEST_FORMAT,\n  requires: ['a', 'b'],\n  contexts: [\n",
    );
    expect(text).toContain("      name: 'probe',\n      requires: ['c'],\n");
    expect(text).toContain(
      "        name: 'thing',\n        requires: ['d', 'e'],\n",
    );
  });

  it('writes no requires where none stands', () => {
    expect(routing(probe({}))).not.toContain('requires');
  });
});
