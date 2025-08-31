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

import type { FindCategory } from "./FindCategory.ts";
import { Table, Tag, Typography } from "antd";
import type { TableColumnsType } from "antd";
import { useEffect, useState } from "react";
import { all, type PagedList } from "@ahoo-wang/fetcher-wow";
import {
  executionFailedSnapshotQueryClient,
  type ExecutionFailedState,
} from "../../services";
const { Paragraph } = Typography;

interface FailedTableProps {
  category: FindCategory;
}

const columns: TableColumnsType<ExecutionFailedState> = [
  {
    title: "ID",
    dataIndex: "id",
    key: "id",
    width: 180,
    fixed: "left",
    render: (id) => <Paragraph copyable>{id}</Paragraph>,
  },
  {
    title: "Function",
    children: [
      {
        title: "Context",
        dataIndex: "function",
        key: "function.contextName",
        render: (func) => func?.contextName,
        width: 120,
      },
      {
        title: "Processor",
        dataIndex: "function",
        key: "function.processorName",
        render: (func) => (
          <Paragraph copyable ellipsis={{ tooltip: func?.processorName }}>
            {func?.processorName}
          </Paragraph>
        ),
        width: 120,
      },
      {
        title: "Name",
        dataIndex: "function",
        key: "function.name",
        render: (func) => (
          <Paragraph copyable ellipsis={{ tooltip: func?.name }}>
            {func?.name}
          </Paragraph>
        ),
        width: 150,
      },
      {
        title: "Kind",
        dataIndex: "function",
        key: "function.functionKind",
        render: (func) => func?.functionKind,
        width: 100,
      },
    ],
  },
  {
    title: "Event Info",
    children: [
      {
        title: "Event ID",
        dataIndex: "eventId",
        key: "eventId.id",
        render: (eventId) => <Paragraph copyable>{eventId?.id}</Paragraph>,
        width: 180,
      },
      {
        title: "Version",
        dataIndex: "eventId",
        key: "eventId.version",
        render: (eventId) => eventId?.version,
        width: 80,
      },
      {
        title: "Aggregate ID",
        dataIndex: "eventId",
        key: "eventId.aggregateId.aggregateId",
        render: (eventId) => (
          <Paragraph copyable>{eventId?.aggregateId.aggregateId}</Paragraph>
        ),
        width: 180,
      },
      {
        title: "Context",
        dataIndex: "eventId",
        key: "eventId.aggregateId.contextName",
        render: (eventId) => eventId?.aggregateId.contextName,
        width: 120,
      },
      {
        title: "Aggregate",
        dataIndex: "eventId",
        key: "eventId.aggregateId.aggregateName",
        render: (eventId) => eventId?.aggregateId.aggregateName,
        width: 120,
      },
    ],
  },
  {
    title: "Retry Info",
    children: [
      {
        title: "Status",
        dataIndex: "status",
        key: "status",
        render: (status) => {
          switch (status) {
            case "FAILED":
              return <Tag color="error">Failed</Tag>;
            case "PREPARED":
              return <Tag color="processing">Prepared</Tag>;
            case "SUCCEEDED":
              return <Tag color="success">Succeeded</Tag>;
            default:
              return <Tag>{status}</Tag>;
          }
        },
        width: 100,
      },
      {
        title: "Retries",
        dataIndex: "retryState",
        key: "retryState.retries",
        render: (retryState) => retryState?.retries,
        width: 80,
      },
      {
        title: "Max Retries",
        dataIndex: ["retrySpec", "maxRetries"],
        key: "retrySpec.maxRetries",
        // render: (retrySpec) => retrySpec?.maxRetries,
        width: 100,
      },
      {
        title: "Recoverable",
        dataIndex: "recoverable",
        key: "recoverable",
        width: 120,
      },
    ],
  },
  {
    title: "Time",
    children: [
      {
        title: "Execute At",
        dataIndex: "executeAt",
        key: "executeAt",
        render: (executeAt) =>
          executeAt && new Date(executeAt).toLocaleString(),
        width: 180,
        fixed: "right",
      },
      {
        title: "Next Retry",
        dataIndex: "retryState",
        key: "retryState.nextRetryAt",
        render: (retryState) =>
          retryState?.nextRetryAt &&
          new Date(retryState.nextRetryAt).toLocaleString(),
        width: 180,
        fixed: "right",
      },
    ],
  },
];
export function FailedTable({ category }: FailedTableProps) {
  const [state, setState] = useState<PagedList<ExecutionFailedState>>({
    total: 0,
    list: [],
  });
  async function fetchData() {
    const pagedList = (await executionFailedSnapshotQueryClient.pagedState({
      condition: all(),
    })) as PagedList<ExecutionFailedState>;
    setState(pagedList);
  }

  useEffect(() => {
    fetchData();
  }, [category]);

  return (
    <Table<ExecutionFailedState>
      rowKey="id"
      columns={columns}
      dataSource={state.list}
      bordered
      scroll={{ x: 1500 }}
    ></Table>
  );
}
