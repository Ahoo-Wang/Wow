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
 * What the analysis decides for the command and query clients of each
 * aggregate, read off the generation model: names, routes, command types,
 * path parameters and events.
 */

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { GeneratorError } from '../../src/api/errors';
import type { AggregateModel } from '../../src/analysis/model';
import { analyzeDocument } from '../support/emission';
import type { WowAggregateOptions } from '../support/specs';
import { wowDocument } from '../support/specs';

function aggregateOf(options: WowAggregateOptions = {}): AggregateModel {
  const { aggregates } = analyzeDocument(wowDocument(options) as OpenAPI).model;
  expect(aggregates).toHaveLength(1);
  return aggregates[0];
}

describe('aggregate analysis', () => {
  it('names the clients of an aggregate and the files they go to', () => {
    const aggregate = aggregateOf({ context: 'sales', aggregate: 'cart' });
    expect(aggregate).toMatchObject({
      contextAlias: 'sales',
      aggregateName: 'cart',
      context: {
        alias: 'sales',
        constantName: 'SALES_BOUNDED_CONTEXT_ALIAS',
      },
      commandClient: {
        file: 'sales/cart/commandClient.ts',
        endpointPathsName: 'CartCommandEndpointPaths',
        className: 'CartCommandClient',
        streamClassName: 'CartStreamCommandClient',
      },
      queryClient: {
        file: 'sales/cart/queryClient.ts',
        resourceName: 'cart',
        state: { name: 'CartState', path: '/sales/cart' },
        fields: { name: 'CartAggregatedFields', path: '/sales/cart' },
        eventTitlesName: 'CartDomainEventTypeMapTitle',
        eventTypeName: 'CartDomainEventType',
        factoryName: 'cartQueryClientFactory',
      },
    });
  });

  it('declares a command per command route: its route member, method, body type and doc', () => {
    const [command] = aggregateOf({ commands: ['create_order'] }).commandClient
      .commands;
    expect(command).toEqual({
      path: '/order/create_order',
      endpointMember: 'CREATE_ORDER',
      httpMethod: 'post',
      methodName: 'createOrder',
      typeName: 'CreateOrderCommand',
      body: { name: 'CreateOrder', path: '/shop/order' },
      bodyKey: 'shop.order.CreateOrder',
      optionalFields: [],
      requestOptional: false,
      pathParameters: [],
      docs: [
        'create_order',
        undefined,
        '- operationId: `shop.order.create_order`',
        '- path: `/order/create_order`',
      ],
    });
  });

  it('declares no alias for a body whose name already ends in Command', () => {
    const commands = aggregateOf({ commands: ['mounted_command', 'pay'] })
      .commandClient.commands;
    expect(commands.map(({ body, typeName }) => [body.name, typeName])).toEqual(
      [
        ['MountedCommand', undefined],
        ['Pay', 'PayCommand'],
      ],
    );
  });

  it('fails when a command type takes the name of another command body', () => {
    const run = () => aggregateOf({ commands: ['foo', 'foo_command'] });
    expect(run).toThrow(GeneratorError);
    expect(run).toThrow(
      'Command foo() of aggregate shop.order generates the type FooCommand for its body shop.order.Foo, but the model of schema shop.order.FooCommand already has that name. Rename the schema shop.order.Foo or shop.order.FooCommand in the document.',
    );
    try {
      run();
    } catch (error) {
      expect((error as GeneratorError).kind).toBe('specification');
    }
  });

  it("fails when a command type takes the name of a model of the aggregate's package", () => {
    const spec = wowDocument({ commands: ['foo'] });
    spec.components.schemas['shop.order.FooCommand'] = { type: 'string' };
    expect(() => analyzeDocument(spec as OpenAPI)).toThrow(
      'Command foo() of aggregate shop.order generates the type FooCommand for its body shop.order.Foo, but the model of schema shop.order.FooCommand already has that name. Rename the schema shop.order.Foo or shop.order.FooCommand in the document.',
    );
  });

  it('lets a model of another package share a command type name', () => {
    const spec = wowDocument({ commands: ['foo'] });
    spec.components.schemas['shop.other.FooCommand'] = { type: 'string' };
    const { aggregates } = analyzeDocument(spec as OpenAPI).model;
    expect(aggregates[0].commandClient.commands[0].typeName).toBe('FooCommand');
  });

  it('turns command names into route members and method names, numbering a clash', () => {
    const commands = aggregateOf({
      commands: ['pay-order', '2fa_reset', 'pay_order'],
    }).commandClient.commands;
    expect(
      commands.map(({ endpointMember, methodName }) => [
        endpointMember,
        methodName,
      ]),
    ).toEqual([
      ['_2FA_RESET', '_2faReset'],
      ['PAY_ORDER', 'payOrder'],
      ['PAY_ORDER_2', 'payOrder'],
    ]);
  });

  it("keeps the path parameters Wow's interceptor does not fill, as identifiers", () => {
    const spec = wowDocument({ commands: ['rename'] });
    const operation = spec.paths['/order/rename'].post;
    delete spec.paths['/order/rename'];
    spec.paths['/tenant/{tenantId}/order/{order-id}/rename'] = {
      post: {
        ...operation,
        parameters: [
          {
            name: 'tenantId',
            in: 'path',
            required: true,
            schema: { type: 'string' },
          },
          {
            name: 'order-id',
            in: 'path',
            required: true,
            schema: { type: 'integer' },
          },
        ],
      },
    };
    const { aggregates } = analyzeDocument(spec as OpenAPI).model;
    const [command] = aggregates[0].commandClient.commands;
    expect(command.pathParameters).toEqual([
      { name: 'orderId', pathName: 'order-id', type: 'number' },
    ]);
    expect(aggregates[0].queryClient.resourceAttribution).toBe(
      'ResourceAttributionPathSpec.TENANT',
    );
  });

  it('takes the path parameters in the order the route holds them', () => {
    const spec = wowDocument({ commands: ['rename'] });
    const operation = spec.paths['/order/rename'].post;
    delete spec.paths['/order/rename'];
    spec.paths['/order/{id}/{customerId}/{mockEnum}/rename'] = {
      post: {
        ...operation,
        parameters: ['customerId', 'id', 'mockEnum'].map(name => ({
          name,
          in: 'path',
          required: true,
          schema: { type: 'string' },
        })),
      },
    };
    const { aggregates } = analyzeDocument(spec as OpenAPI).model;
    expect(
      aggregates[0].commandClient.commands[0].pathParameters.map(
        ({ name }) => name,
      ),
    ).toEqual(['id', 'customerId', 'mockEnum']);
  });

  it('declares the events of an aggregate with their titles and bodies', () => {
    const { events } = aggregateOf({
      events: [
        { name: 'order_created', title: "Customer's order" },
        { name: 'order-paid' },
      ],
    }).queryClient;
    expect(events).toEqual([
      {
        memberName: 'order_created',
        title: "Customer's order",
        body: { name: 'OrderCreated', path: '/shop/order' },
      },
      {
        memberName: "'order-paid'",
        title: 'order-paid',
        body: { name: 'OrderPaid', path: '/shop/order' },
      },
    ]);
  });

  it('prefixes a factory or method name that would start with a digit', () => {
    const aggregate = aggregateOf({ aggregate: '3d', commands: ['2fa'] });
    expect(aggregate.queryClient.factoryName).toBe('_3dQueryClientFactory');
    expect(aggregate.commandClient.commands[0].methodName).toBe('_2fa');
  });

  it('queries the route segment the aggregate uses, not its name', () => {
    expect(
      aggregateOf({ aggregate: 'order', resource: 'sales-order' }).queryClient
        .resourceName,
    ).toBe('sales-order');
  });
});
