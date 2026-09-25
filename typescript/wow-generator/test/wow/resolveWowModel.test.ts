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
 * How the Wow model is read off a document, one rule at a time, each on a
 * small Wow document (`wowDocument`) changed in one place. The two real Wow
 * documents are held by `wowModelContract.test.ts`, malformed metadata by
 * `malformedMetadata.test.ts`.
 */

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { openApiDocument } from '../../src/openapi/document';
import type { AggregateDefinition, WowModel } from '../../src/wow/model';
import { isWowDocument, withDocOverride } from '../../src/wow/model';
import { resolveWowModel } from '../../src/wow/resolveWowModel';
import { document, getOperation, wowDocument } from '../support/specs';

type Json = Record<string, any>;

const COMMAND_PATH = '/order/create_order';
const COMMAND_BODY = 'shop.order.CreateOrder';

/** Resolves a document, and checks it was left as it was. */
function resolve(spec: Json): WowModel {
  const before = structuredClone(spec);
  const model = resolveWowModel(openApiDocument(spec as OpenAPI));
  expect(spec).toEqual(before);
  return model;
}

function onlyAggregate(model: WowModel): AggregateDefinition {
  const aggregates = [...model.contexts.values()].flatMap(set => [...set]);
  expect(aggregates).toHaveLength(1);
  return aggregates[0];
}

function commandOf(spec: Json): Json {
  return spec.paths[COMMAND_PATH].post;
}

function renamePath(spec: Json, from: string, to: string) {
  spec.paths[to] = spec.paths[from];
  delete spec.paths[from];
}

describe('resolveWowModel', () => {
  it('reads nothing from a document that is not a Wow one', () => {
    const model = resolve(document({ '/items': getOperation('listItems') }));
    expect(model).toEqual({
      contextAlias: undefined,
      contexts: new Map(),
      aggregateTags: new Set(),
      schemaDocOverrides: new Map(),
      warnings: [],
    });
    expect(isWowDocument(model)).toBe(false);
  });

  it('reads an aggregate with its commands, events, state and fields', () => {
    const model = resolve(
      wowDocument({ commands: ['create_order', 'pay_order'] }),
    );
    expect(model.contextAlias).toBe('shop');
    expect(model.aggregateTags).toEqual(new Set(['shop.order']));
    expect(model.warnings).toEqual([]);
    expect(isWowDocument(model)).toBe(true);
    const aggregate = onlyAggregate(model);
    expect(aggregate.aggregate).toEqual({
      tag: { name: 'shop.order' },
      contextAlias: 'shop',
      aggregateName: 'order',
    });
    expect(aggregate.resourceName).toBe('order');
    expect(aggregate.state.key).toBe('shop.order.OrderState');
    expect(aggregate.fields.key).toBe('shop.order.OrderAggregatedFields');
    expect([...aggregate.commands.keys()]).toEqual([
      'create_order',
      'pay_order',
    ]);
    expect(aggregate.commands.get('create_order')).toMatchObject({
      name: 'create_order',
      method: 'post',
      path: COMMAND_PATH,
      pathParameters: [],
      summary: 'create_order',
      schema: { key: COMMAND_BODY },
    });
    expect([...aggregate.events.values()]).toEqual([
      {
        name: 'order_created',
        title: 'order_created',
        schema: {
          key: 'shop.order.OrderCreated',
          schema: expect.objectContaining({ type: 'object' }),
        },
      },
    ]);
  });

  it('reads an aggregate whose tag the document does not declare', () => {
    const model = resolve(wowDocument({ declareTag: false }));
    expect(onlyAggregate(model).aggregate.tag).toEqual({ name: 'shop.order' });
  });

  it('reads the route segment of an aggregate off its snapshot routes', () => {
    const model = resolve(wowDocument({ resource: 'sales-order' }));
    expect(onlyAggregate(model).resourceName).toBe('sales-order');
  });

  it('names the route segment after the aggregate without snapshot routes', () => {
    const spec = wowDocument();
    renamePath(spec, '/order/snapshot/single/state', '/order/state');
    renamePath(spec, '/order/snapshot/count', '/order/count');
    renamePath(spec, '/tenant/{tenantId}/order/snapshot/count', '/count');
    expect(onlyAggregate(resolve(spec)).resourceName).toBe('order');
  });

  it('ignores a tag that names no aggregate', () => {
    const spec = wowDocument();
    spec.tags.push({ name: 'Items' }, { name: 'a.b.c' });
    expect(resolve(spec).aggregateTags).toEqual(new Set(['shop.order']));
  });

  it('leaves out an aggregate tag without Wow routes, silently', () => {
    const spec = wowDocument();
    spec.tags.push({ name: 'shop.idle' });
    const model = resolve(spec);
    expect(model.aggregateTags).toEqual(new Set(['shop.order']));
    expect(model.warnings).toEqual([]);
  });

  describe('commands', () => {
    it.each([
      [
        'the operation that sends any command',
        (spec: Json) => {
          commandOf(spec).operationId = 'wow.command.send';
        },
      ],
      [
        'an operation id of another shape',
        (spec: Json) => {
          commandOf(spec).operationId = 'create_order';
        },
      ],
      [
        'an operation without a success response',
        (spec: Json) => {
          commandOf(spec).responses = { '400': { description: 'Bad' } };
        },
      ],
      [
        'an inline success response',
        (spec: Json) => {
          commandOf(spec).responses['200'] = { description: 'OK' };
        },
      ],
      [
        'a success response other than CommandOk',
        (spec: Json) => {
          spec.components.responses.Other = { description: 'Other' };
          commandOf(spec).responses['200'] = {
            $ref: '#/components/responses/Other',
          };
        },
      ],
      [
        'a success response outside the components',
        (spec: Json) => {
          commandOf(spec).responses['200'] = { $ref: '#/paths/elsewhere' };
        },
      ],
      [
        'an operation without a request body',
        (spec: Json) => {
          delete commandOf(spec).requestBody;
        },
      ],
      [
        'a body that references no schema component',
        (spec: Json) => {
          commandOf(spec).requestBody.content['application/json'].schema = {
            $ref: '#/definitions/CreateOrder',
          };
        },
      ],
    ])('skips %s', (_, breakIt) => {
      const spec = wowDocument();
      breakIt(spec);
      expect(onlyAggregate(resolve(spec)).commands.size).toBe(0);
    });

    it('follows a success response through response components', () => {
      const spec = wowDocument();
      spec.components.responses.Accepted = {
        $ref: '#/components/responses/wow.CommandOk',
      };
      commandOf(spec).responses['200'] = {
        $ref: '#/components/responses/Accepted',
      };
      expect(onlyAggregate(resolve(spec)).commands.size).toBe(1);
    });

    it('reads a request body component', () => {
      const spec = wowDocument();
      spec.components.requestBodies = {
        CreateOrder: commandOf(spec).requestBody,
      };
      commandOf(spec).requestBody = {
        $ref: '#/components/requestBodies/CreateOrder',
      };
      expect(
        onlyAggregate(resolve(spec)).commands.get('create_order')?.schema.key,
      ).toBe(COMMAND_BODY);
    });

    it('merges the path item parameters into the command', () => {
      const spec = wowDocument();
      renamePath(spec, COMMAND_PATH, '/order/{id}/create_order');
      spec.paths['/order/{id}/create_order'].parameters = [
        { name: 'id', in: 'path', required: true, description: 'shared' },
        { name: 'trace', in: 'header' },
      ];
      spec.paths['/order/{id}/create_order'].post.parameters = [
        { name: 'id', in: 'path', required: true, description: 'own' },
      ];
      const command = onlyAggregate(resolve(spec)).commands.get(
        'create_order',
      )!;
      expect(command.path).toBe('/order/{id}/create_order');
      expect(command.pathParameters).toEqual([
        { name: 'id', in: 'path', required: true, description: 'own' },
      ]);
    });

    it('leaves a command of a tag that is no aggregate out', () => {
      const spec = wowDocument();
      commandOf(spec).tags = ['Items'];
      const model = resolve(spec);
      expect(onlyAggregate(model).commands.size).toBe(0);
      expect(model.warnings).toEqual([]);
    });
  });

  describe('events', () => {
    it('titles an event by its name when it has no title', () => {
      const spec = wowDocument();
      delete spec.components.schemas[
        'shop.order.OrderAggregatedDomainEventStream'
      ].properties.body.items.anyOf[0].title;
      expect(
        onlyAggregate(resolve(spec)).events.get('order_created')?.title,
      ).toBe('order_created');
    });

    it('reads no events from a list that is a schema component', () => {
      const spec = wowDocument();
      spec.components.schemas.EventList = { type: 'array' };
      spec.paths['/order/{id}/event/list'].post.responses['200'].content[
        'application/json'
      ].schema = { $ref: '#/components/schemas/EventList' };
      expect(onlyAggregate(resolve(spec)).events.size).toBe(0);
    });

    it('reads no events of a tag that is no aggregate', () => {
      const spec = wowDocument();
      spec.paths['/order/{id}/event/list'].post.tags = ['Items'];
      expect(onlyAggregate(resolve(spec)).events.size).toBe(0);
    });
  });

  it('reads the fields of a request body component', () => {
    const spec = wowDocument();
    const count = spec.paths['/order/snapshot/count'].post;
    spec.components.requestBodies = { Count: count.requestBody };
    count.requestBody = { $ref: '#/components/requestBodies/Count' };
    expect(onlyAggregate(resolve(spec)).fields.key).toBe(
      'shop.order.OrderAggregatedFields',
    );
  });

  it('reads no events, state or fields from a document without components', () => {
    const spec = wowDocument();
    delete spec.components;
    for (const path of [
      COMMAND_PATH,
      '/order/snapshot/single/state',
      '/order/snapshot/count',
      '/tenant/{tenantId}/order/snapshot/count',
    ]) {
      delete spec.paths[path];
    }
    const model = resolve(spec);
    expect(model.contexts.size).toBe(0);
    expect(model.aggregateTags).toEqual(new Set());
  });
});

describe('the doc comments the Wow metadata lends', () => {
  it('lends a command body its operation summary and description', () => {
    const spec = wowDocument();
    commandOf(spec).description = 'Places an order.';
    const model = resolve(spec);
    expect(model.schemaDocOverrides.get(COMMAND_BODY)).toEqual({
      title: 'create_order',
      description: 'Places an order.',
    });
    const body = spec.components.schemas[COMMAND_BODY];
    expect(
      withDocOverride(body, model.schemaDocOverrides.get(COMMAND_BODY)),
    ).toEqual({
      ...body,
      title: 'create_order',
      description: 'Places an order.',
    });
  });

  it('keeps the title and description a schema has', () => {
    const spec = wowDocument();
    Object.assign(spec.components.schemas[COMMAND_BODY], {
      title: 'Create',
      description: 'Own',
    });
    const override = resolve(spec).schemaDocOverrides.get(COMMAND_BODY);
    expect(override).toEqual({ title: 'Create', description: 'Own' });
  });

  it('lends a body shared by two commands the first summary', () => {
    const spec = wowDocument({ commands: ['create_order', 'place_order'] });
    spec.paths['/order/place_order'].post.requestBody =
      commandOf(spec).requestBody;
    expect(resolve(spec).schemaDocOverrides.get(COMMAND_BODY)?.title).toBe(
      'create_order',
    );
  });

  it('lends an event body the title of its domain event', () => {
    const model = resolve(
      wowDocument({ events: [{ name: 'order_created', title: 'Created' }] }),
    );
    expect(model.schemaDocOverrides.get('shop.order.OrderCreated')).toEqual({
      title: 'Created',
    });
  });

  it('adds the fields a schema lacks after its own, in the order it lends them', () => {
    // A command without a summary lends no title but still sets the field,
    // so an event's title lent later takes the place the command gave it.
    const spec = wowDocument({
      events: [{ name: 'order_created', title: 'Created' }],
    });
    delete commandOf(spec).summary;
    commandOf(spec).description = '';
    const stream =
      spec.components.schemas['shop.order.OrderAggregatedDomainEventStream'];
    stream.properties.body.items.anyOf[0].properties.body = {
      $ref: `#/components/schemas/${COMMAND_BODY}`,
    };
    const model = resolve(spec);
    const lent = withDocOverride(
      spec.components.schemas[COMMAND_BODY],
      model.schemaDocOverrides.get(COMMAND_BODY),
    );
    expect(JSON.stringify(lent)).toBe(
      '{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"title":"Created","description":""}',
    );
  });

  it('lends nothing without Wow metadata', () => {
    const body = { type: 'object' };
    expect(withDocOverride(body, undefined)).toBe(body);
  });
});
