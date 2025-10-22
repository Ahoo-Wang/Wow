import { QueryClientFactory, QueryClientOptions, ResourceAttributionPathSpec } from "@ahoo-wang/fetcher-wow";
import { AddressChanged, OrderAggregatedFields, OrderCreated, OrderPaid, OrderReceived, OrderShipped, WowExampleOrderState } from "./types";

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
    contextAlias: 'example',
    aggregateName: 'order',
    resourceAttribution: ResourceAttributionPathSpec.TENANT,
};

type DOMAIN_EVENT_TYPES = AddressChanged | OrderCreated | OrderPaid | OrderReceived | OrderShipped;

export const orderQueryClientFactory = new QueryClientFactory<WowExampleOrderState, OrderAggregatedFields | string, DOMAIN_EVENT_TYPES>(DEFAULT_QUERY_CLIENT_OPTIONS);
