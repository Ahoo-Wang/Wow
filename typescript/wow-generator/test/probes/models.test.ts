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
 * Probes of the models: doc comments, nullable references, discriminators,
 * recursive maps and names of globals.
 */

import { describe, expect, it } from 'vitest';
import { generateCompiling } from '../support/probes';
import { document, getOperation } from '../support/specs';

describe('comment terminators in the document', () => {
  it('escapes */ in descriptions, titles and summaries', async () => {
    const { read } = await generateCompiling(
      document(
        {
          '/jobs': getOperation(
            'listJobs',
            { $ref: '#/components/schemas/Job' },
            { summary: 'Runs */5 * * * *', description: 'glob **/*.ts */' },
          ),
        },
        {
          Job: {
            type: 'object',
            title: 'A job */',
            description: 'cron */5 * * * *',
            properties: {
              cron: { type: 'string', description: 'e.g. */10 * * * *' },
            },
            required: ['cron'],
          },
        },
      ),
    );
    expect(read('types.ts')).toContain('cron *\\/5 * * * *');
    expect(read('itemsApiClient.ts')).toContain('Runs *\\/5 * * * *');
  });
});

describe('nullable references', () => {
  it('admits null for {nullable, allOf: [$ref]}', async () => {
    await generateCompiling(
      document(
        { '/m': getOperation('m', { $ref: '#/components/schemas/Model' }) },
        {
          Model: {
            type: 'object',
            properties: {
              nulRef: {
                nullable: true,
                allOf: [{ $ref: '#/components/schemas/Other' }],
              },
            },
            required: ['nulRef'],
          },
          Other: { type: 'object', properties: { x: { type: 'string' } } },
        },
      ),
      `import type { Model } from './out/index.js';
       const nothing: Model['nulRef'] = null;
       void nothing;`,
    );
  });
});

describe('discriminators, recursive maps and global names', () => {
  it('narrows a discriminated union by its property', async () => {
    await generateCompiling(
      document(
        { '/p': getOperation('pet', { $ref: '#/components/schemas/Pet' }) },
        {
          Pet: {
            oneOf: [
              { $ref: '#/components/schemas/Cat' },
              { $ref: '#/components/schemas/Dog' },
            ],
            discriminator: {
              propertyName: 'petType',
              mapping: { cat: '#/components/schemas/Cat' },
            },
          },
          Cat: {
            type: 'object',
            properties: {
              petType: { type: 'string' },
              meow: { type: 'boolean' },
            },
            required: ['petType', 'meow'],
          },
          Dog: {
            type: 'object',
            properties: {
              petType: { type: 'string' },
              bark: { type: 'boolean' },
            },
            required: ['petType', 'bark'],
          },
        },
      ),
      `import type { Pet } from './out/index.js';
       declare const pet: Pet;
       if (pet.petType === 'cat') { const meow: boolean = pet.meow; void meow; }
       if (pet.petType === 'Dog') { const bark: boolean = pet.bark; void bark; }`,
    );
  });

  it('generates a map of its own type', async () => {
    const { read } = await generateCompiling(
      document(
        { '/d': getOperation('dict', { $ref: '#/components/schemas/Dict' }) },
        {
          Dict: {
            type: 'object',
            additionalProperties: { $ref: '#/components/schemas/Dict' },
          },
        },
      ),
    );
    expect(read('types.ts')).toContain('[key: string]: Dict;');
  });

  it('keeps models named Record and Response from shadowing the globals', async () => {
    const { read } = await generateCompiling(
      document(
        {
          '/r': getOperation('record', { $ref: '#/components/schemas/Record' }),
          '/s': getOperation('response', {
            $ref: '#/components/schemas/Response',
          }),
          '/t': {
            get: { tags: ['Items'], operationId: 'raw', responses: {} },
          },
        },
        {
          Record: { type: 'object', properties: { a: { type: 'string' } } },
          Response: { type: 'object', properties: { b: { type: 'string' } } },
        },
      ),
      `import { ItemsApiClient, type Response as ResponseModel } from './out/index.js';
       declare const client: ItemsApiClient;
       const raw: Promise<Response> = client.raw();
       const model: Promise<ResponseModel> = client.response();
       void raw; void model;`,
    );
    expect(read('itemsApiClient.ts')).toContain('Response as _Response');
  });
});
