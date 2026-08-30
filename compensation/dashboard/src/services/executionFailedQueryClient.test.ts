import { beforeEach, describe, expect, it, vi } from "vitest";
import { filter, pagedQuery, singleQuery } from "@ahoo-wang/fetcher-wow";
import {
  aggregateExecutionFailedSnapshots,
  queryExecutionFailedPage,
  queryExecutionFailedState,
} from "./executionFailedQueryClient.ts";

const mocks = vi.hoisted(() => ({
  createSnapshotQueryClient: vi.fn(),
  pagedState: vi.fn(),
  singleState: vi.fn(),
  aggregate: vi.fn(),
}));

vi.mock("../generated", () => ({
  executionFailedQueryClientFactory: {
    createSnapshotQueryClient: mocks.createSnapshotQueryClient.mockReturnValue({
      pagedState: mocks.pagedState,
      singleState: mocks.singleState,
      aggregate: mocks.aggregate,
    }),
  },
}));

describe("executionFailedQueryClient", () => {
  beforeEach(() => {
    mocks.pagedState.mockClear();
    mocks.singleState.mockClear();
    mocks.aggregate.mockClear();
  });

  it("creates the generated client behind the dashboard service boundary", () => {
    expect(mocks.createSnapshotQueryClient).toHaveBeenCalledWith({
      contextAlias: "",
    });
  });

  it("delegates paged queries with request context and cancellation", async () => {
    const query = pagedQuery({ filter: filter.aggregateId("failed-1") });
    const attributes = { source: "dashboard" };
    const abortController = new AbortController();

    await queryExecutionFailedPage(query, attributes, abortController);

    expect(mocks.pagedState).toHaveBeenCalledWith(
      query,
      attributes,
      abortController,
    );
  });

  it("delegates single-state queries with request context and cancellation", async () => {
    const query = singleQuery({ filter: filter.aggregateId("failed-1") });
    const attributes = { source: "dashboard" };
    const abortController = new AbortController();

    await queryExecutionFailedState(query, attributes, abortController);

    expect(mocks.singleState).toHaveBeenCalledWith(
      query,
      attributes,
      abortController,
    );
  });

  it("delegates snapshot aggregation with cancellation", async () => {
    const query = { metrics: [{ type: "COUNT", alias: "count" }] } as never;
    const attributes = { source: "analytics" };
    const abortController = new AbortController();

    await aggregateExecutionFailedSnapshots(query, attributes, abortController);

    expect(mocks.aggregate).toHaveBeenCalledWith(
      query,
      attributes,
      abortController,
    );
  });
});
