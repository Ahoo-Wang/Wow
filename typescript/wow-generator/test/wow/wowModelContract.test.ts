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
 * The Wow model of the two Wow documents: every aggregate with its commands,
 * events, state, query fields and resource name, the tags that belong to
 * aggregates, the doc comments the Wow metadata lends to schemas that have
 * none, and the warnings. The golden `expected/wow-model/*.json` was recorded
 * from the resolver that mutated the document (before refactor batch B6), so
 * it also holds the pure resolver to the old result.
 *
 * Accept an intentional change with `-u` and review the diff of the golden.
 */

import { createHash } from 'node:crypto';
import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { Project } from 'ts-morph';
import { describe, expect, it } from 'vitest';
import { SilentLogger } from '../../src/api/logger';
import { AggregateResolver } from '../../src/aggregate';
import type { BoundedContextAggregates } from '../../src/aggregate';
import { parseOpenAPI } from '../../src/input/parsers';
import { createCodeGenerator, recordingLogger } from '../support/generation';

interface Resolved {
  contextAlias?: string;
  contexts: BoundedContextAggregates;
  aggregateTags: ReadonlySet<string>;
  schemaOverrides: Record<string, { title?: string; description?: string }>;
  warnings: string[];
}

/** Resolves the Wow model of a document, leaving the document as it was. */
function resolve(openAPI: OpenAPI): Resolved {
  const before = structuredClone(openAPI);
  const logger = recordingLogger();
  const resolver = new AggregateResolver(openAPI, logger);
  const contexts = resolver.resolve();
  const schemaOverrides: Resolved['schemaOverrides'] = {};
  for (const [key, schema] of Object.entries(
    openAPI.components?.schemas ?? {},
  )) {
    const original = before.components!.schemas![key] as Record<
      string,
      unknown
    >;
    const current = schema as Record<string, unknown>;
    for (const field of ['title', 'description'] as const) {
      if (current[field] !== original[field]) {
        (schemaOverrides[key] ??= {})[field] = current[field] as string;
        current[field] = original[field];
        if (!(field in original)) delete current[field];
      }
    }
  }
  expect(openAPI).toEqual(before);
  return {
    contextAlias: openAPI.info['x-wow-context-alias'],
    contexts,
    aggregateTags: resolver.aggregateTags(),
    schemaOverrides,
    warnings: logger.warnings,
  };
}

/** The resolved model as plain data, in the order the generator reads it. */
function summarize(resolved: Resolved) {
  return {
    contextAlias: resolved.contextAlias ?? null,
    aggregateTags: [...resolved.aggregateTags].sort(),
    contexts: Object.fromEntries(
      [...resolved.contexts].map(([alias, aggregates]) => [
        alias,
        [...aggregates].map(aggregate => ({
          tag: aggregate.aggregate.tag.name,
          contextAlias: aggregate.aggregate.contextAlias,
          aggregateName: aggregate.aggregate.aggregateName,
          resourceName: aggregate.resourceName,
          state: aggregate.state.key,
          fields: aggregate.fields.key,
          commands: [...aggregate.commands].map(([name, command]) => ({
            name,
            method: command.method,
            path: command.path,
            pathParameters: command.pathParameters.map(
              parameter => parameter.name,
            ),
            schema: command.schema.key,
            operationId: command.operation.operationId,
            summary: command.summary ?? null,
            description: command.description ?? null,
          })),
          events: [...aggregate.events].map(([name, event]) => ({
            name,
            title: event.title,
            schema: event.schema.key,
          })),
        })),
      ]),
    ),
    schemaOverrides: Object.fromEntries(
      Object.entries(resolved.schemaOverrides).sort(([left], [right]) =>
        left < right ? -1 : left > right ? 1 : 0,
      ),
    ),
    warnings: resolved.warnings,
  };
}

const DOCUMENTS = [
  ['demo', 'test/demo.spec.json'],
  ['compensation', 'test/compensation.spec.json'],
] as const;

describe('the Wow model of the Wow documents', () => {
  it.each(DOCUMENTS)(
    'resolves %s as the golden records',
    async (name, path) => {
      const resolved = resolve(await parseOpenAPI(path));
      await expect(
        `${JSON.stringify(summarize(resolved), null, 2)}\n`,
      ).toMatchFileSnapshot(`../../expected/wow-model/${name}.json`);
    },
  );

  // Spot checks written by hand, so the golden is not the only witness.
  it.each([
    {
      path: 'test/demo.spec.json',
      contextAlias: 'example',
      tag: 'example.order',
      resourceName: 'sales-order',
      state: 'example.order.WowExampleOrderState',
      fields: 'example.order.OrderAggregatedFields',
      command: [
        'create_order',
        'post',
        '/tenant/{tenantId}/owner/{ownerId}/sales-order',
        ['tenantId', 'ownerId'],
      ],
      event: ['address_changed', 'example.order.AddressChanged'],
    },
    {
      path: 'test/compensation.spec.json',
      contextAlias: 'compensation',
      tag: 'compensation.execution_failed',
      resourceName: 'execution_failed',
      state: 'compensation.execution_failed.ExecutionFailedState',
      fields: 'compensation.execution_failed.ExecutionFailedAggregatedFields',
      command: [
        'apply_execution_failed',
        'put',
        '/execution_failed/{id}/apply_execution_failed',
        ['id'],
      ],
      event: [
        'compensation_prepared',
        'compensation.execution_failed.CompensationPrepared',
      ],
    },
  ])(
    'reads $tag of $path',
    async ({
      path,
      contextAlias,
      tag,
      resourceName,
      state,
      fields,
      command,
      event,
    }) => {
      const resolved = resolve(await parseOpenAPI(path));
      expect(resolved.contextAlias).toBe(contextAlias);
      expect(resolved.aggregateTags).toContain(tag);
      const aggregate = [...resolved.contexts.get(contextAlias)!].find(
        candidate => candidate.aggregate.tag.name === tag,
      )!;
      expect(aggregate.resourceName).toBe(resourceName);
      expect(aggregate.state.key).toBe(state);
      expect(aggregate.fields.key).toBe(fields);
      const [commandName, method, commandPath, pathParameters] = command;
      const definition = aggregate.commands.get(commandName as string)!;
      expect(definition).toMatchObject({ method, path: commandPath });
      expect(definition.pathParameters.map(({ name }) => name)).toEqual(
        pathParameters,
      );
      const [eventName, eventSchema] = event;
      expect(aggregate.events.get(eventName)?.schema.key).toBe(eventSchema);
    },
  );

  // With `schemaDocs: 'full'` a model's doc embeds its whole schema, so the
  // doc comments the Wow metadata lends show up there too, key order and all.
  it.each(DOCUMENTS)(
    'documents the models of %s in full as the golden records',
    async (name, path) => {
      const project = new Project({ useInMemoryFileSystem: true });
      const { files } = await createCodeGenerator(
        {
          inputPath: path,
          outputDir: '/out',
          schemaDocs: 'full',
          logger: new SilentLogger(),
        },
        project,
      ).generate();
      const fs = project.getFileSystem();
      const hashes = Object.fromEntries(
        files.map(file => [
          file.slice('/out/'.length),
          createHash('sha256').update(fs.readFileSync(file)).digest('hex'),
        ]),
      );
      if (name === 'demo') {
        // A command body without a title takes its operation's summary.
        expect(fs.readFileSync('/out/example/cart/types.ts')).toMatch(
          /"title": "view_cart",\n \* {3}"description": ""\n \* }\n[^]*?export type ViewCart = /,
        );
      }
      await expect(`${JSON.stringify(hashes, null, 2)}\n`).toMatchFileSnapshot(
        `../../expected/wow-model/${name}-full-docs.json`,
      );
    },
  );
});
