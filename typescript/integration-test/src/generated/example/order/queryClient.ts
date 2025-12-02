import { QueryClientFactory, QueryClientOptions, ResourceAttributionPathSpec } from "@ahoo-wang/fetcher-wow";
import { AddressChanged, OrderAggregatedFields, OrderCreated, OrderPaid, OrderReceived, OrderShipped, WowExampleOrderState } from "./types";
import { EXAMPLE_BOUNDED_CONTEXT_ALIAS } from "../boundedContext";

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
    contextAlias: EXAMPLE_BOUNDED_CONTEXT_ALIAS,
    aggregateName: 'order',
    resourceAttribution: ResourceAttributionPathSpec.TENANT,
};

export enum OrderDomainEventTypeMapTitle {
    address_changed = '收货地址已修改',
    order_created = 'order_created',
    order_paid = 'order_paid',
    order_received = 'order_received',
    order_shipped = 'order_shipped'
}

export type OrderDomainEventType = AddressChanged | OrderCreated | OrderPaid | OrderReceived | OrderShipped;

export const orderQueryClientFactory = new QueryClientFactory<WowExampleOrderState, OrderAggregatedFields | string, OrderDomainEventType>(DEFAULT_QUERY_CLIENT_OPTIONS);
