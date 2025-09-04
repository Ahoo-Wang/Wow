import { Descriptions, Typography, Tag, Statistic, Flex, Skeleton } from "antd";
import type { DescriptionsProps } from "antd";

import {
  executionFailedSnapshotQueryClient,
  type ExecutionFailedState,
} from "../../services";
import { ErrorDetails } from "./ErrorDetails.tsx";
import { useEffect, useState } from "react";
import { aggregateId, singleQuery } from "@ahoo-wang/fetcher-wow";
import useSWR from "swr";
const { Timer } = Statistic;
function formatIsoDateTime(timeAt: number | undefined): string {
  if (!timeAt) {
    return "-";
  }
  return new Date(timeAt).toLocaleString();
}

const { Text } = Typography;

export interface FailedDetailsProps {
  state: ExecutionFailedState;
}

export function FailedDetails({ state }: FailedDetailsProps) {
  // 基本信息
  const basicItems: DescriptionsProps["items"] = [
    {
      key: "id",
      label: "ID",
      children: (
        <Text code copyable>
          {state.id}
        </Text>
      ),
      span: 1,
    },
    {
      key: "status",
      label: "Status",
      children: (
        <>
          {state.status === "FAILED" && <Tag color="error">Failed</Tag>}
          {state.status === "PREPARED" && (
            <Tag color="processing">Prepared</Tag>
          )}
          {state.status === "SUCCEEDED" && <Tag color="success">Succeeded</Tag>}
          {!state.status && "-"}
        </>
      ),
      span: 1,
    },
    {
      key: "executeAt",
      label: "Execute At",
      children: formatIsoDateTime(state.executeAt),
      span: 1,
    },
    {
      key: "recoverable",
      label: "Recoverable",
      children: (
        <>
          {state.recoverable === "RECOVERABLE" && (
            <Tag color="success">Recoverable</Tag>
          )}
          {state.recoverable === "UNRECOVERABLE" && (
            <Tag color="error">Unrecoverable</Tag>
          )}
          {state.recoverable === "UNKNOWN" && (
            <Tag color="warning">Unknown</Tag>
          )}
          {!state.recoverable && "-"}
        </>
      ),
      span: 1,
    },
    {
      key: "isRetryable",
      label: "Is Retryable",
      children: state.isRetryable ? (
        <Tag color="success">Yes</Tag>
      ) : (
        <Tag color="error">No</Tag>
      ),
      span: 1,
    },
  ];

  // EventId 信息
  const eventIdItems: DescriptionsProps["items"] = [
    {
      key: "contextName",
      label: "Context Name",
      children: state.eventId.aggregateId.contextName,
      span: 1,
    },
    {
      key: "aggregateName",
      label: "Aggregate Name",
      children: state.eventId.aggregateId.aggregateName,
      span: 1,
    },
    {
      key: "eventId",
      label: "Event ID",
      children: (
        <Text code copyable>
          {state.eventId.id}
        </Text>
      ),
      span: 1,
    },
    {
      key: "eventVersion",
      label: "Event Version",
      children: (
        <Text code copyable>
          {state.eventId.version}
        </Text>
      ),
      span: 1,
    },
    {
      key: "tenantId",
      label: "Tenant ID",
      children: (
        <Text code copyable>
          {state.eventId.aggregateId.tenantId}
        </Text>
      ),
      span: 1,
    },
    {
      key: "aggregateId",
      label: "Aggregate ID",
      children: (
        <Text code copyable>
          {state.eventId.aggregateId.aggregateId}
        </Text>
      ),
      span: 1,
    },
  ];

  // 函数信息
  const functionItems: DescriptionsProps["items"] = [
    {
      key: "contextName",
      label: "Context Name",
      children: state.function.contextName,
      span: 1,
    },
    {
      key: "processorName",
      label: "Processor Name",
      children: state.function.processorName,
      span: 1,
    },
    {
      key: "functionName",
      label: "Function Name",
      children: state.function.name,
      span: 1,
    },
    {
      key: "functionKind",
      label: "Function Kind",
      children: state.function.functionKind,
      span: 1,
    },
  ];

  // 重试信息
  const retryItems: DescriptionsProps["items"] = [
    {
      key: "retries",
      label: "Retries",
      children: (
        <Text>
          {state.retryState.retries}({state.retrySpec.maxRetries})
        </Text>
      ),
      span: 1,
    },
    {
      key: "retryAt",
      label: "Retry At",
      children: new Date(state.retryState.retryAt).toLocaleString(),
      span: 1,
    },
    {
      key: "nextRetryAt",
      label: "Next Retry At",
      children: (
        <Timer
          type="countdown"
          value={state.retryState.nextRetryAt}
          format="HH:mm:ss"
          valueStyle={{ fontSize: "14px" }}
        />
      ),
      span: 2,
    },
    {
      key: "minBackoff",
      label: "Min Backoff (ms)",
      children: state.retrySpec.minBackoff.toString(),
      span: 1,
    },
    {
      key: "executionTimeout",
      label: "Execution Timeout (ms)",
      children: state.retrySpec.executionTimeout.toString(),
      span: 1,
    },
  ];

  return (
    <Flex gap="small" vertical>
      <Descriptions bordered column={3} items={basicItems} size="small" />
      <Descriptions bordered column={3} items={eventIdItems} size="small" />
      <Descriptions bordered column={2} items={functionItems} size="small" />
      <Descriptions bordered column={3} items={retryItems} size="small" />
      <ErrorDetails error={state.error}></ErrorDetails>
    </Flex>
  );
}
export interface FetchingFailedDetailsProps {
  id: string;
}
export function FetchingFailedDetails({ id }: FetchingFailedDetailsProps) {
  const { data, error, isLoading } = useSWR(id, () =>
    executionFailedSnapshotQueryClient.singleState<ExecutionFailedState>(
      singleQuery({ condition: aggregateId(id) }),
    ),
  );
  if (error) {
    return (
      <Flex justify="center" align="center" style={{ minHeight: 100 }}>
        <Text type="danger">Failed to load data: {error.message}</Text>
      </Flex>
    );
  }
  if (isLoading) {
    return (
      <Flex gap="small" vertical>
        <Skeleton active />
        <Skeleton active />
        <Skeleton active />
      </Flex>
    );
  }
  return <FailedDetails state={data!} />;
}
