import {
  QueryClientFactory,
  QueryClientOptions,
  ResourceAttributionPathSpec,
} from '@ahoo-wang/fetcher-wow';
import {
  CartAggregatedFields,
  CartItemAdded,
  CartItemRemoved,
  CartQuantityChanged,
  CartState,
} from './types';

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
  contextAlias: 'example',
  aggregateName: 'cart',
  resourceAttribution: ResourceAttributionPathSpec.OWNER,
};

export enum CartDomainEventTypes {
  cart_item_added = '商品已加入购物车',
  cart_item_removed = 'cart_item_removed',
  cart_quantity_changed = 'cart_quantity_changed',
}

export type CartDomainEventType =
  | CartItemAdded
  | CartItemRemoved
  | CartQuantityChanged;

export const cartQueryClientFactory = new QueryClientFactory<
  CartState,
  CartAggregatedFields | string,
  CartDomainEventType
>(DEFAULT_QUERY_CLIENT_OPTIONS);
