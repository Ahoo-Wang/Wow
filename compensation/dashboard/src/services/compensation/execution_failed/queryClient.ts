import {
  QueryClientFactory,
  type QueryClientOptions,
  ResourceAttributionPathSpec,
} from "@ahoo-wang/fetcher-wow";
import {
  type CompensationPrepared,
  ExecutionFailedAggregatedFields,
  type ExecutionFailedApplied,
  type  ExecutionFailedCreated,
  type  ExecutionFailedState,
  type  ExecutionSuccessApplied,
  type  FunctionChanged,
  type  RecoverableMarked,
  type  RetrySpecApplied,
} from "./types";

const DEFAULT_QUERY_CLIENT_OPTIONS: QueryClientOptions = {
    contextAlias: 'compensation',
    aggregateName: 'execution_failed',
    resourceAttribution: ResourceAttributionPathSpec.NONE,
};

type DOMAIN_EVENT_TYPES = CompensationPrepared | ExecutionFailedApplied | ExecutionFailedCreated | ExecutionSuccessApplied | FunctionChanged | RecoverableMarked | RetrySpecApplied;

export const executionFailedQueryClientFactory = new QueryClientFactory<ExecutionFailedState, ExecutionFailedAggregatedFields | string, DOMAIN_EVENT_TYPES>(DEFAULT_QUERY_CLIENT_OPTIONS);
