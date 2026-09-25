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

import type { OpenAPI } from '@ahoo-wang/fetcher-openapi';
import { describe, expect, it } from 'vitest';
import { emitDocument } from '../support/emission';
import { wowDocument } from '../support/specs';

function generate(spec: Record<string, any>) {
  return emitDocument(spec as OpenAPI).file('shop/order/commandClient.ts');
}

describe('command clients', () => {
  it('writes the endpoint paths, command types and both clients', () => {
    const file = generate(wowDocument({ commands: ['create_order'] }));
    expect(
      file
        .getEnumOrThrow('OrderCommandEndpointPaths')
        .getMembers()[0]
        .getText(),
    ).toBe("CREATE_ORDER = '/order/create_order'");
    expect(
      file
        .getTypeAliasOrThrow('CreateOrderCommand')
        .getTypeNodeOrThrow()
        .getText(),
    ).toBe('CommandBody<CreateOrder>');
    const method = file
      .getClassOrThrow('OrderCommandClient')
      .getMethodOrThrow('createOrder');
    expect(method.getDecorators()[0].getText()).toBe(
      '@post(OrderCommandEndpointPaths.CREATE_ORDER)',
    );
    expect(
      method.getParameters().map(parameter => parameter.getText()),
    ).toEqual([
      '@request() commandRequest: CommandRequest<CreateOrderCommand>',
      '@attribute() attributes?: Record<string, unknown>',
    ]);
    expect(
      file.getClassOrThrow('OrderStreamCommandClient').getConstructors(),
    ).toEqual([]);
  });

  it('merges apiMetadata over the bounded context default', () => {
    const file = generate(wowDocument());
    const client = file.getClassOrThrow('OrderCommandClient');
    expect(client.getPropertyOrThrow('apiMetadata').getText()).toBe(
      'readonly apiMetadata: ApiMetadata;',
    );
    expect(client.getConstructors()[0].getBodyText()).toBe(
      'this.apiMetadata = { ...DEFAULT_COMMAND_CLIENT_OPTIONS, ...apiMetadata };',
    );
    expect(file.getText()).toContain(
      "import { SHOP_BOUNDED_CONTEXT_ALIAS } from '../boundedContext.js';",
    );
  });

  it('turns command names into endpoint members and method names', () => {
    const file = generate(
      wowDocument({ commands: ['pay-order', '2fa_reset'] }),
    );
    expect(
      file
        .getEnumOrThrow('OrderCommandEndpointPaths')
        .getMembers()
        .map(member => member.getName()),
    ).toEqual(['_2FA_RESET', 'PAY_ORDER']);
    const client = file.getClassOrThrow('OrderCommandClient');
    expect(client.getMethods().map(method => method.getName())).toEqual([
      '_2faReset',
      'payOrder',
    ]);
  });

  it('keeps path parameters it does not leave to the interceptor', () => {
    const spec = wowDocument({ commands: ['rename'] });
    const [path] = Object.keys(spec.paths).filter(key =>
      key.endsWith('/rename'),
    );
    const operation = spec.paths[path].post;
    delete spec.paths[path];
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
            schema: { type: 'string' },
          },
        ],
      },
    };
    const method = generate(spec)
      .getClassOrThrow('OrderCommandClient')
      .getMethodOrThrow('rename');
    expect(method.getParameters()[0].getText()).toBe(
      "@path('order-id') orderId: string",
    );
  });
});
