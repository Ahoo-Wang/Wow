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
 * Wow metadata the resolver cannot read (review finding R2-30), table by
 * table: each case breaks one piece of a well-formed Wow document. Metadata
 * of an aggregate that is malformed is a `specification` error naming the
 * operation and what is wrong with it; metadata that is merely incomplete
 * skips the command or the aggregate with a warning.
 */

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import { AggregateResolver } from '../../src/aggregate';
import { recordingLogger } from '../support/generation';
import { wowDocument } from '../support/specs';

type Json = Record<string, any>;

/** Resolves the Wow model of a document; returns the warnings it gives. */
function resolve(document: Json): { aggregates: number; warnings: string[] } {
  const logger = recordingLogger();
  const contexts = new AggregateResolver(document as OpenAPI, logger).resolve();
  return {
    aggregates: [...contexts.values()].reduce(
      (count, aggregates) => count + aggregates.size,
      0,
    ),
    warnings: logger.warnings,
  };
}

const STREAM = 'shop.order.OrderAggregatedDomainEventStream';
const STREAM_REF = `#/components/schemas/${STREAM}`;

function operation(document: Json, path: string): Json {
  return document.paths[path].post;
}

function malformed(
  name: string,
  operationId: string,
  problem: string,
  breakIt: (document: Json) => void,
) {
  return { name, operationId, problem, breakIt };
}

describe('malformed Wow metadata', () => {
  it.each([
    malformed(
      'a state response that is not a $ref',
      'shop.order.snapshot_state.single',
      'its 200 application/json response is not a $ref to the state schema',
      document => {
        operation(document, '/order/snapshot/single/state').responses[
          '200'
        ].content['application/json'].schema = { type: 'object' };
      },
    ),
    malformed(
      'an event list that is not an array of a $ref',
      'shop.order.event.list_query',
      'its 200 application/json response is not an array of a $ref to the event stream schema',
      document => {
        operation(document, '/order/{id}/event/list').responses['200'].content[
          'application/json'
        ].schema = {
          type: 'array',
          items: { type: 'object' },
        };
      },
    ),
    malformed(
      'an event stream without anyOf',
      'shop.order.event.list_query',
      `the event stream schema ${STREAM_REF} has no properties.body.items.anyOf listing the domain events`,
      document => {
        document.components.schemas[STREAM] = {
          type: 'object',
          properties: {},
        };
      },
    ),
    malformed(
      'a domain event without a name',
      'shop.order.event.list_query',
      `domain event 0 of ${STREAM_REF} needs a properties.name.const and a properties.body that is a $ref to the event schema`,
      document => {
        delete document.components.schemas[STREAM].properties.body.items
          .anyOf[0].properties.name.const;
      },
    ),
    malformed(
      'an inline event body',
      'shop.order.event.list_query',
      `domain event 0 of ${STREAM_REF} needs a properties.name.const and a properties.body that is a $ref to the event schema`,
      document => {
        document.components.schemas[
          STREAM
        ].properties.body.items.anyOf[0].properties.body = { type: 'object' };
      },
    ),
    malformed(
      'a snapshot count without a request body',
      'shop.order.snapshot.count',
      'it has no request body',
      document => {
        delete operation(document, '/order/snapshot/count').requestBody;
      },
    ),
    malformed(
      'query fields that are not a schema reference',
      'shop.order.snapshot.count',
      'x-wow-query-fields must be a schema reference',
      document => {
        operation(document, '/order/snapshot/count').requestBody[
          'x-wow-query-fields'
        ] = ['state.id'];
      },
    ),
    malformed(
      'a pre-8.11 condition without a field reference',
      'shop.order.snapshot.count',
      'its request body has no x-wow-query-fields, and its condition schema has no field $ref to read the query fields from',
      document => {
        document.components.schemas.Condition = {
          type: 'object',
          properties: { field: { type: 'string' } },
        };
        operation(document, '/order/snapshot/count').requestBody = {
          content: {
            'application/json': {
              schema: { $ref: '#/components/schemas/Condition' },
            },
          },
        };
      },
    ),
  ])('reports $name', ({ operationId, problem, breakIt }) => {
    const document = wowDocument();
    breakIt(document);
    let error: unknown;
    try {
      resolve(document);
    } catch (thrown) {
      error = thrown;
    }
    expect(error).toBeInstanceOf(GeneratorError);
    expect(error).toMatchObject({
      kind: 'specification',
      message: `Cannot read the Wow metadata of ${operationId}: ${problem}. wow-generator reads documents from Wow 8.10 or later.`,
    });
  });

  it('reports a cycle of command responses', () => {
    const document = wowDocument();
    operation(document, '/order/create_order').responses['200'] = {
      $ref: '#/components/responses/Loop',
    };
    document.components.responses.Loop = {
      $ref: '#/components/responses/Loop',
    };
    expect(() => resolve(document)).toThrow(
      new GeneratorError(
        'specification',
        'Cyclic component reference: #/components/responses/Loop',
      ),
    );
  });

  it('reads the query fields of a pre-8.11 condition', () => {
    const document = wowDocument();
    document.components.schemas.Condition = {
      type: 'object',
      properties: {
        field: {
          $ref: '#/components/schemas/shop.order.OrderAggregatedFields',
        },
      },
    };
    const count = operation(document, '/order/snapshot/count');
    count.requestBody = {
      content: {
        'application/json': {
          schema: { $ref: '#/components/schemas/Condition' },
        },
      },
    };
    operation(document, '/tenant/{tenantId}/order/snapshot/count').requestBody =
      count.requestBody;
    expect(resolve(document)).toEqual({ aggregates: 1, warnings: [] });
  });

  it('leaves alone malformed metadata of a tag that is no aggregate', () => {
    const document = wowDocument();
    const state = operation(document, '/order/snapshot/single/state');
    state.tags = ['Items'];
    state.responses['200'].content['application/json'].schema = {
      type: 'object',
    };
    expect(resolve(document).warnings).toEqual([
      'Skipping aggregate shop.order: the document has no shop.order.snapshot_state.single operation, so it generates neither command nor query clients for it.',
    ]);
  });
});

describe('incomplete Wow metadata', () => {
  it.each([
    {
      name: 'an aggregate without state',
      breakIt: (document: Json) => {
        delete document.paths['/order/snapshot/single/state'];
      },
      aggregates: 0,
      warning:
        'Skipping aggregate shop.order: the document has no shop.order.snapshot_state.single operation, so it generates neither command nor query clients for it.',
    },
    {
      name: 'an aggregate without query fields',
      breakIt: (document: Json) => {
        delete document.paths['/order/snapshot/count'];
        delete document.paths['/tenant/{tenantId}/order/snapshot/count'];
      },
      aggregates: 0,
      warning:
        'Skipping aggregate shop.order: the document has no shop.order.snapshot.count operation, so it generates neither command nor query clients for it.',
    },
    {
      name: 'a command whose body is not a $ref',
      breakIt: (document: Json) => {
        operation(document, '/order/create_order').requestBody.content[
          'application/json'
        ].schema = { type: 'object' };
      },
      aggregates: 1,
      warning:
        'Skipping command shop.order.create_order (POST /order/create_order): its application/json request body is not a $ref to a component schema.',
    },
  ])('skips $name with a warning', ({ breakIt, aggregates, warning }) => {
    const document = wowDocument();
    breakIt(document);
    expect(resolve(document)).toEqual({ aggregates, warnings: [warning] });
  });
});
