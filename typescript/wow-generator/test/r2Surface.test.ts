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
 * The second review round's findings on the generator's public surface: the
 * names the Manifest's clients take, the failures a Manifest must meet with a
 * specification error rather than an internal one, and what the README and the
 * reference promise about the CLI and the programmatic API.
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Project } from 'ts-morph';
import { afterAll, describe, expect, it } from 'vitest';
import { EXIT_CODES } from '../src/api/errors';
import type { Logger } from '../src/api/logger';
import { runGenerate } from '../src/cli/runGenerate';
import { PROJECT_SEAM } from '../src/pipeline/seams';
import { manifest, schema } from './support/manifestProbes';
import {
  coldDirectory,
  generateCold,
  PACKAGE_ROOT,
  removeDirectories,
} from './support/generation';
import { generateCompiling } from './support/probes';

const directories: string[] = [];
afterAll(() => removeDirectories(directories));

/** The state every probe aggregate here has. */
const STATE = { schema: schema('a.ThingState') };

/** A Manifest of the contexts given, each `{ name, aggregates }`, over one state type. */
function contexts(
  ...entries: { name: string; aggregates: Record<string, unknown>[] }[]
) {
  return {
    format: 1,
    contexts: entries,
    $defs: {
      'a.ThingState': {
        type: 'object',
        properties: { n: { type: 'integer' } },
      },
    },
  };
}

/**
 * The Kotlin contract's name of an aggregate's object, `ContractSources.pascal`
 * (compiler/manifest-tools): the name's words split on `-`, `_`, `.` and
 * spaces, each with its first letter upper-cased and the rest kept as written.
 */
function kotlinPascal(name: string): string {
  return name
    .split(/[-_. ]/)
    .filter(word => word !== '')
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join('');
}

describe('names a Manifest gives apart', () => {
  it('meets two aggregates whose names give one type name (order-item, order_item) with a specification error, not an internal one', async () => {
    const result = await generateCold(
      contexts({
        name: 'probe',
        aggregates: [
          { name: 'order-item', state: STATE },
          { name: 'order_item', state: STATE },
        ],
      }),
      directories,
    );
    expect(result.errors.join('\n')).not.toContain('wow-generator bug');
    expect(result.exitCode).not.toBe(EXIT_CODES.internal);
  });

  it("meets a context's client named as an aggregate's (context order-command, aggregate order: OrderCommandClient) with a specification error, not an internal one", async () => {
    const result = await generateCold(
      contexts({
        name: 'order-command',
        aggregates: [{ name: 'order', state: STATE }],
      }),
      directories,
    );
    expect(result.errors.join('\n')).not.toContain('wow-generator bug');
    expect(result.exitCode).not.toBe(EXIT_CODES.internal);
  });

  it("writes a context client that parses for an aggregate whose camel-cased name is no identifier (2fa): not `this.'2fa' = …`", async () => {
    // Exit 0 today: the check before writing looks for names, not for syntax.
    const { read } = await generateCompiling(
      contexts({ name: 'probe', aggregates: [{ name: '2fa', state: STATE }] }),
    );
    expect(read('probe/client.ts')).not.toContain("this.'2fa'");
  });

  it('gives two recursive types whose camel-cased names coincide (ABTree, Abtree) marks of their own', async () => {
    const tree = (number: Record<string, unknown>, self: string) => ({
      type: 'object',
      properties: {
        amount: number,
        children: { type: 'array', items: { $ref: schema(self) } },
      },
    });
    await generateCompiling(
      manifest(
        {},
        {
          'a.ThingState': {
            type: 'object',
            properties: {
              x: { $ref: schema('a.ABTree') },
              y: { $ref: schema('a.Abtree') },
            },
          },
          'a.ABTree': tree({ type: 'number', format: 'decimal' }, 'a.ABTree'),
          'a.Abtree': tree({ type: 'integer', format: 'int64' }, 'a.Abtree'),
        },
      ),
    );
  });
});

describe('names the Kotlin contract shares', () => {
  it("names an aggregate's member of the contract as the Kotlin contract names its object, acronyms kept (orderID is OrderID, HTTPLog is HTTPLog)", async () => {
    const { read } = await generateCompiling(
      contexts({
        name: 'probe',
        aggregates: [
          { name: 'orderID', state: STATE },
          { name: 'HTTPLog', state: STATE },
        ],
      }),
    );
    const contract = read('probe/contract.ts');
    // The contract's doc says its members are named "as the Kotlin `ProbeContract` names them".
    expect(contract).toContain('as the Kotlin `ProbeContract` names them');
    for (const aggregate of ['orderID', 'HTTPLog']) {
      expect(contract).toMatch(
        new RegExp(`\\n  ${kotlinPascal(aggregate)}: \\w+,`),
      );
    }
  });

  it('keeps the words of a command after an acronym apart in its method (CreateURLShortcut is not createUrlshortcut)', async () => {
    const { read } = await generateCompiling(
      manifest(
        {
          commands: [
            {
              name: 'CreateURLShortcut',
              schema: schema('a.CreateURLShortcut'),
              routes: [
                {
                  method: 'POST',
                  path: '/probe/thing/{id}/create-url-shortcut',
                },
              ],
            },
          ],
        },
        {
          'a.CreateURLShortcut': {
            type: 'object',
            properties: { n: { type: 'integer' } },
          },
        },
      ),
    );
    const client = read('probe/thing/commandClient.ts');
    // Its body type keeps the acronym: the method beside it should keep the word boundary too.
    expect(client).toContain('body: CreateURLShortcut');
    expect(client).toMatch(/\n {2}create(URL|Url)Shortcut\(/);
  });

  it("gives an aggregate's descriptor the facts the Kotlin contract says it carries under the same names in camel case", async () => {
    const kdoc = readFileSync(
      join(
        PACKAGE_ROOT,
        '../../compiler/manifest-tools/src/main/kotlin/me/ahoo/wow/manifest/tools/ContractSources.kt',
      ),
      'utf8',
    );
    expect(kdoc).toContain(
      "generator's `<Aggregate>Aggregate` constant carries the same facts under the same names, in camel case",
    );
    const { read } = await generateCompiling(
      manifest({ queries: [{ model: 'snapshot', routes: [] }] }),
    );
    const descriptor = read('probe/thing/aggregate.ts');
    // CONTEXT, NAME, QUALIFIED_NAME, OWNERSHIP, ID_SOURCE, SNAPSHOT, QUERY_MODELS of the Kotlin aggregate object.
    const missing = [
      'context',
      'name',
      'qualifiedName',
      'ownership',
      'idSource',
      'snapshot',
      'queryModels',
    ].filter(fact => !new RegExp(`\\n  ${fact}: `).test(descriptor));
    expect(missing).toEqual([]);
  });
});

describe('what the README and the reference promise', () => {
  it('names the configuration in the summary line as the README says it does', async () => {
    const dir = coldDirectory('r2-summary', directories);
    const input = join(dir, 'manifest.json');
    writeFileSync(input, JSON.stringify(manifest()));
    const config = join(dir, 'wow-generator.config.json');
    writeFileSync(config, JSON.stringify({}));
    const lines: string[] = [];
    const logger: Logger = {
      debug() {},
      info(message: string) {
        lines.push(message);
      },
      warn() {},
      error() {},
    };
    const exitCode = await runGenerate(
      { input, output: join(dir, 'out'), config },
      logger,
      { [PROJECT_SEAM]: new Project({ skipAddingFilesFromTsConfig: true }) },
    );
    expect(exitCode).toBe(EXIT_CODES.success);
    const readme = readFileSync(join(PACKAGE_ROOT, 'README.md'), 'utf8');
    const readmePromisesAbsolute = readme.includes(
      'The summary line names the absolute path of the configuration it read',
    );
    const summary = lines.find(line => line.startsWith('Generated ')) ?? '';
    expect(
      { summary, absolute: summary.includes(` with ${config}`) },
      `README.md: "The summary line names the absolute path of the configuration it read" (${config})`,
    ).toEqual({ summary, absolute: readmePromisesAbsolute });
  });

  it('lists every exit code of EXIT_CODES in the programmatic API reference', () => {
    const reference = readFileSync(
      join(
        PACKAGE_ROOT,
        '../../documentation/docs/en/reference/typescript/wow-generator/programmatic-api.md',
      ),
      'utf8',
    );
    const unlisted = Object.entries(EXIT_CODES)
      .filter(([key, code]) => !reference.includes(`\`${key}\` ${code}`))
      .map(([key, code]) => `${key} ${code}`);
    expect(unlisted).toEqual([]);
  });
});
