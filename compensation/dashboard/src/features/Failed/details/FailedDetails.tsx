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

import type { DescriptionsProps } from "antd";
import { Descriptions, Flex, Statistic, Tag, Typography } from "antd";

import { type ExecutionFailedState } from "../../../generated";
import { ErrorDetails } from "./ErrorDetails.tsx";
import { formatDate } from "../../../utils/dates.ts";

const { Timer } = Statistic;

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
        </>
      ),
      span: 1,
    },
    {
      key: "executeAt",
      label: "Execute At",
      children: formatDate(state.executeAt),
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
      children: formatDate(state.retryState.retryAt),
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
    <Flex gap="small" vertical style={{ height: "100%" }}>
      <Descriptions bordered column={6} items={basicItems} size="small" />
      <Descriptions bordered column={4} items={functionItems} size="small" />
      <Descriptions bordered column={3} items={eventIdItems} size="small" />
      <Descriptions bordered column={6} items={retryItems} size="small" />
      <ErrorDetails error={state.error}></ErrorDetails>
    </Flex>
  );
}
