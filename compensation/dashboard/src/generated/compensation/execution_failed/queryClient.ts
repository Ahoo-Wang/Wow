import { QueryClientFactory, QueryClientOptions, ResourceAttributionPathSpec } from "@ahoo-wang/fetcher-wow";
import { CompensationPrepared, ExecutionFailedAggregatedFields, ExecutionFailedApplied, ExecutionFailedCreated, ExecutionFailedState, ExecutionSuccessApplied, FunctionChanged, RecoverableMarked, RetrySpecApplied } from "./types";

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
    contextAlias: 'compensation',
    aggregateName: 'execution_failed',
    resourceAttribution: ResourceAttributionPathSpec.NONE,
};

export enum ExecutionFailedDomainEventTypes {
    compensation_prepared = 'compensation_prepared',
    execution_failed_applied = 'execution_failed_applied',
    execution_failed_created = 'execution_failed_created',
    execution_success_applied = 'execution_success_applied',
    function_changed = 'function_changed',
    recoverable_marked = 'recoverable_marked',
    retry_spec_applied = 'retry_spec_applied'
}

export type ExecutionFailedDomainEventType = CompensationPrepared | ExecutionFailedApplied | ExecutionFailedCreated | ExecutionSuccessApplied | FunctionChanged | RecoverableMarked | RetrySpecApplied;

export const executionFailedQueryClientFactory = new QueryClientFactory<ExecutionFailedState, ExecutionFailedAggregatedFields | string, ExecutionFailedDomainEventType>(DEFAULT_QUERY_CLIENT_OPTIONS);
