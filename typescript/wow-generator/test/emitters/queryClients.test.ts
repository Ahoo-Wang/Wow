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
import { emitDocument, openAPIDocument } from '../support/emission';
import type { WowAggregateOptions } from '../support/specs';
import { wowDocument } from '../support/specs';

function queryClient(options: WowAggregateOptions = {}) {
  return emitDocument(wowDocument(options) as OpenAPI).file(
    `${options.context ?? 'shop'}/${options.aggregate ?? 'order'}/queryClient.ts`,
  );
}

describe('query clients', () => {
  it('writes the factory of an aggregate into the module of its aggregate', () => {
    const file = queryClient({
      context: 'sales',
      aggregate: 'cart',
      events: [
        { name: 'cart_created', title: 'Cart created' },
        { name: 'item_added', title: 'Item added' },
      ],
    });
    expect(file.getFilePath()).toBe('/out/sales/cart/queryClient.ts');
    expect(
      file
        .getImportDeclarationOrThrow('@ahoo-wang/wow-client')
        .getNamedImports()
        .map(specifier => specifier.getName()),
    ).toEqual([
      'QueryClientFactory',
      'QueryClientOptions',
      'ResourceAttributionPathSpec',
    ]);
    expect(
      file
        .getVariableDeclarationOrThrow('DEFAULT_QUERY_CLIENT_OPTIONS')
        .getInitializerOrThrow()
        .getText()
        .replace(/\s+/g, ' '),
    ).toBe(
      "{ contextAlias: SALES_BOUNDED_CONTEXT_ALIAS, aggregateName: 'cart', resourceAttribution: ResourceAttributionPathSpec.NONE, }",
    );
    expect(
      file
        .getEnumOrThrow('CartDomainEventTypeMapTitle')
        .getMembers()
        .map(member => member.getText()),
    ).toEqual(["cart_created = 'Cart created'", "item_added = 'Item added'"]);
    expect(
      file
        .getTypeAliasOrThrow('CartDomainEventType')
        .getTypeNodeOrThrow()
        .getText(),
    ).toBe('CartCreated | ItemAdded');
    expect(
      file
        .getVariableDeclarationOrThrow('cartQueryClientFactory')
        .getInitializerOrThrow()
        .getText(),
    ).toBe(
      'new QueryClientFactory<CartState, `${CartAggregatedFields}`, CartDomainEventType>(DEFAULT_QUERY_CLIENT_OPTIONS)',
    );
  });

  it('types the events of an aggregate without any as never', () => {
    const file = queryClient({ events: [] });
    expect(
      file
        .getTypeAliasOrThrow('OrderDomainEventType')
        .getTypeNodeOrThrow()
        .getText(),
    ).toBe('never');
    expect(
      file.getEnumOrThrow('OrderDomainEventTypeMapTitle').getMembers(),
    ).toEqual([]);
  });

  it('writes no query client for a document without aggregates', () => {
    const { project } = emitDocument(
      openAPIDocument({ paths: {}, components: { schemas: {} } }),
    );
    expect(project.getSourceFiles()).toEqual([]);
  });
});
