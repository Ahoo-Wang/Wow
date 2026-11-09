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
 * Probes of the names a Manifest's generated modules bind: a type of
 * `$defs` keeps clear of the TypeScript globals, the `@ahoo-wang/wow-client`
 * names and the generator's own declarations beside which it is imported, a
 * route's path variable of the method's own parameters, and a union stays one
 * member of the intersection around it.
 */

import { readdirSync, readFileSync, statSync } from 'fs';
import * as path from 'path';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';
import type { ManifestDocument } from '../../src/manifest/document';
import { generatedNames } from '../../src/manifest/names';
import { PACKAGE_ROOT } from '../support/generation';
import { generateCompiling } from '../support/probes';

const schema = (key: string) => `#/$defs/${key}`;

/** A Manifest of probe.thing whose types are named after what the generated modules bind. */
const SHADOWING: ManifestDocument = {
  format: 1,
  contexts: [
    {
      name: 'probe',
      aggregates: [
        {
          name: 'thing',
          state: { schema: schema('a.Record') },
          queries: [
            { model: 'snapshot', routes: [] },
            { model: 'events', routes: [] },
          ],
          commands: [
            {
              name: 'Act',
              schema: schema('a.Promise'),
              routes: [{ method: 'POST', path: '/probe/thing/{id}/do' }],
            },
          ],
          events: [
            { name: 'Done', schema: schema('a.ThingEvent') },
            { name: 'Undone', schema: schema('a.CommandClient') },
          ],
        },
      ],
      routes: [
        {
          name: 'summary',
          route: { method: 'POST', path: '/probe/summaries' },
          request: { $ref: schema('a.ProbeManifest') },
          response: { $ref: schema('a.ThingAggregate') },
        },
      ],
    },
  ],
  $defs: Object.fromEntries(
    [
      'a.Record',
      'a.Promise',
      'a.ThingEvent',
      'a.CommandClient',
      'a.ProbeManifest',
      'a.ThingAggregate',
    ].map(key => [
      key,
      {
        type: 'object',
        required: ['labels'],
        properties: {
          labels: { type: 'object', additionalProperties: { type: 'string' } },
        },
      },
    ]),
  ),
} as unknown as ManifestDocument;

/** The `.ts` files under `dir`. */
function sources(dir: string): string[] {
  return readdirSync(dir).flatMap(name => {
    const full = path.join(dir, name);
    if (statSync(full).isDirectory()) return sources(full);
    return full.endsWith('.ts') ? [full] : [];
  });
}

/**
 * The names a generated module binds besides the Manifest's types: what it
 * imports from `@ahoo-wang/wow-client`, what it declares (but in `types.ts`,
 * which declares the types), and the globals it refers to, a name neither
 * declared nor imported. Read from the syntax alone.
 */
function boundNames(file: string): string[] {
  const source = ts.createSourceFile(
    file,
    readFileSync(file, 'utf8'),
    ts.ScriptTarget.Latest,
  );
  const local = new Set<string>();
  const bound = new Set<string>();
  const referenced = new Set<string>();
  const declaresTypes = path.basename(file) === 'types.ts';
  const visit = (node: ts.Node): void => {
    if (
      ts.isImportDeclaration(node) &&
      ts.isStringLiteral(node.moduleSpecifier)
    ) {
      const fromClient = node.moduleSpecifier.text.startsWith(
        '@ahoo-wang/wow-client',
      );
      const bindings = node.importClause?.namedBindings;
      if (bindings !== undefined && ts.isNamedImports(bindings)) {
        for (const element of bindings.elements) {
          local.add(element.name.text);
          if (fromClient)
            bound.add((element.propertyName ?? element.name).text);
        }
      }
    } else if (
      (ts.isClassDeclaration(node) ||
        ts.isInterfaceDeclaration(node) ||
        ts.isTypeAliasDeclaration(node) ||
        ts.isFunctionDeclaration(node) ||
        ts.isTypeParameterDeclaration(node)) &&
      node.name !== undefined
    ) {
      local.add(node.name.text);
      if (!declaresTypes && !ts.isTypeParameterDeclaration(node)) {
        bound.add(node.name.text);
      }
    } else if (ts.isVariableDeclaration(node) && ts.isIdentifier(node.name)) {
      local.add(node.name.text);
      if (!declaresTypes) bound.add(node.name.text);
    } else if (ts.isTypeReferenceNode(node) && ts.isIdentifier(node.typeName)) {
      referenced.add(node.typeName.text);
    } else if (
      ts.isExpressionWithTypeArguments(node) &&
      ts.isIdentifier(node.expression)
    ) {
      referenced.add(node.expression.text);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);
  for (const name of referenced) if (!local.has(name)) bound.add(name);
  // A value name never meets a type's PascalCase one.
  return [...bound].filter(name => /^[A-Z]/.test(name));
}

describe('the names of a Manifest', () => {
  it('qualifies a type a generated module would bind under its name by its package, so every module compiles', async () => {
    const { read } = await generateCompiling(
      SHADOWING,
      `import {
         ProbeRoutes, ThingCommandClient, ThingStateClient,
         type ARecord, type APromise, type AThingEvent, type ACommandClient,
         type AProbeManifest, type AThingAggregate,
       } from './out/index.js';
       declare const routes: ProbeRoutes;
       const state: ARecord = { labels: { colour: 'red' } };
       const body: APromise = { labels: {} };
       const answer: Promise<AThingAggregate> = routes.summary({ labels: {} } satisfies AProbeManifest);
       const events: (AThingEvent | ACommandClient)[] = [];
       void [state, body, answer, events, new ThingCommandClient().act('t1', body), new ThingStateClient()];`,
    );
    const types = read('probe/types.ts');
    expect(types).toContain('export interface ARecord {');
    expect(types).toContain('labels: Record<string, string>;');
    expect(types).not.toMatch(/interface (Record|Promise|ThingEvent) /);
  });

  it.each([
    ['catalog', 'eshop-catalog'],
    ['basket', 'eshop-basket'],
    ['ordering', 'eshop-ordering'],
    ['inventory', 'eshop-inventory'],
    ['payment', 'eshop-payment'],
  ])(
    'reserves every name the %s clients bind besides their types',
    (service, manifest) => {
      const document = JSON.parse(
        readFileSync(
          path.join(PACKAGE_ROOT, 'test', 'manifests', `${manifest}.json`),
          'utf8',
        ),
      ) as ManifestDocument;
      const reserved = generatedNames(document);
      const files = sources(
        path.join(PACKAGE_ROOT, 'expected', 'eshop-manifest', service),
      );
      expect(files.length).toBeGreaterThan(0);
      const unreserved = files.flatMap(file =>
        boundNames(file)
          .filter(name => !reserved.has(name))
          .map(name => `${path.relative(PACKAGE_ROOT, file)}: ${name}`),
      );
      expect(unreserved).toEqual([]);
    },
  );

  it('reserves every name the clients of service routes and shadowing types bind besides their types', async () => {
    for (const document of [
      SHADOWING,
      JSON.parse(
        readFileSync(
          path.join(
            PACKAGE_ROOT,
            'test',
            'fixtures',
            'service-routes.manifest.json',
          ),
          'utf8',
        ),
      ) as ManifestDocument,
    ]) {
      const { output } = await generateCompiling(document);
      const reserved = generatedNames(document);
      const unreserved = sources(output).flatMap(file =>
        boundNames(file)
          .filter(name => !reserved.has(name))
          .map(name => `${path.relative(output, file)}: ${name}`),
      );
      expect(unreserved).toEqual([]);
    }
  });

  it("names a route's path variable apart from the method's own parameters and from reserved words", async () => {
    const variables = ['options', 'body', 'query', 'class'];
    const { read } = await generateCompiling(
      {
        format: 1,
        contexts: [
          {
            name: 'probe',
            aggregates: [],
            routes: [
              {
                name: 'pick',
                route: {
                  method: 'POST',
                  path: `/probe/${variables.map(name => `{${name}}`).join('/')}`,
                },
                parameters: [
                  ...variables.map(name => ({
                    name,
                    in: 'path',
                    schema: { type: 'string' },
                    required: true,
                  })),
                  {
                    name: 'options',
                    in: 'query',
                    schema: { type: 'string' },
                    required: false,
                  },
                ],
                request: { type: 'string' },
                response: { type: 'string' },
              },
            ],
          },
        ],
        $defs: {},
      },
      `import { ProbeRoutes } from './out/index.js';
       declare const routes: ProbeRoutes;
       const answer: Promise<string> = routes.pick('o', 'b', 'q', 'c', 'the body', { options: 'x' }, { headers: {} });
       void answer;`,
    );
    const routes = read('probe/routes.ts');
    expect(routes).toContain(
      'pick(options2: string, body2: string, query2: string, class_: string, body: string, query?: { readonly options?: string; }, options?: RequestOptions)',
    );
    expect(routes).toContain(
      'path: { options: options2, body: body2, query: query2, class: class_ }',
    );
  });

  it('keeps a union one member of the intersection around it, so a missing required property is refused', async () => {
    const { read } = await generateCompiling(
      {
        format: 1,
        contexts: [
          {
            name: 'probe',
            aggregates: [
              { name: 'choice', state: { schema: schema('a.ChoiceState') } },
            ],
          },
        ],
        $defs: {
          'a.ChoiceState': {
            allOf: [
              {
                anyOf: [
                  {
                    type: 'object',
                    required: ['kind', 'a'],
                    properties: { kind: { const: 'a' }, a: { type: 'string' } },
                  },
                  {
                    type: 'object',
                    required: ['kind', 'b'],
                    properties: { kind: { const: 'b' }, b: { type: 'string' } },
                  },
                ],
              },
              {
                type: 'object',
                required: ['revision'],
                properties: { revision: { type: 'integer' } },
              },
            ],
          },
        },
      },
      `import type { ChoiceState } from './out/index.js';
       const complete: ChoiceState = { kind: 'a', a: 'x', revision: 1 };
       // @ts-expect-error A choice without its revision.
       const missing: ChoiceState = { kind: 'a', a: 'x' };
       void [complete, missing];`,
    );
    expect(read('probe/types.ts')).toContain(
      "export type ChoiceState = ({ kind: 'a'; a: string; } | { kind: 'b'; b: string; }) & { revision: number; };",
    );
  });
});
