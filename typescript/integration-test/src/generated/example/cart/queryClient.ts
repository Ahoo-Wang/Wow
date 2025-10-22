import { QueryClientFactory, QueryClientOptions, ResourceAttributionPathSpec } from "@ahoo-wang/fetcher-wow";
import { CartAggregatedFields, CartItemAdded, CartItemRemoved, CartQuantityChanged, CartState } from "./types";

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
    contextAlias: 'example',
    aggregateName: 'cart',
    resourceAttribution: ResourceAttributionPathSpec.OWNER,
};

type DOMAIN_EVENT_TYPES = CartItemAdded | CartItemRemoved | CartQuantityChanged;

export const cartQueryClientFactory = new QueryClientFactory<CartState, CartAggregatedFields | string, DOMAIN_EVENT_TYPES>(DEFAULT_QUERY_CLIENT_OPTIONS);
