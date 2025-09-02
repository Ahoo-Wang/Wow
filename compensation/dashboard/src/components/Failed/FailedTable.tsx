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

import { Table, Tag, Typography, Statistic, Drawer } from "antd";
import type { TableColumnsType } from "antd";
import { type PagedList } from "@ahoo-wang/fetcher-wow";
import type { ExecutionFailedState } from "../../services";
import { FailedDetails } from "./FailedDetails";
import { useState } from "react";

const { Text } = Typography;
const { Timer } = Statistic;

interface FailedTableProps {
  onPaginationChange?: (page: number, pageSize: number) => void;
  pagedList: PagedList<ExecutionFailedState>;
}

const columns: TableColumnsType<ExecutionFailedState> = [
  {
    title: "ID",
    dataIndex: "id",
    key: "id",
    width: 100,
    fixed: "left",
    render: (id) => (
      <Text ellipsis={true} copyable>
        {id}
      </Text>
    ),
  },
  {
    title: "Function",
    children: [
      {
        title: "Context",
        dataIndex: "function",
        key: "function.contextName",
        render: (func) => (
          <Text copyable ellipsis={{ tooltip: func?.contextName }}>
            {func?.contextName}
          </Text>
        ),
        width: 120,
      },
      {
        title: "Processor",
        dataIndex: "function",
        key: "function.processorName",
        render: (func) => (
          <Text copyable ellipsis={{ tooltip: func?.processorName }}>
            {func?.processorName}
          </Text>
        ),
        width: 120,
      },
      {
        title: "Name",
        dataIndex: "function",
        key: "function.name",
        render: (func) => (
          <Text copyable ellipsis={{ tooltip: func?.name }}>
            {func?.name}
          </Text>
        ),
        width: 150,
      },
      {
        title: "Kind",
        dataIndex: "function",
        key: "function.functionKind",
        render: (func) => <Text>{func?.functionKind}</Text>,
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
        render: (eventId) => (
          <Text ellipsis={true} copyable>
            {eventId?.id}
          </Text>
        ),
        width: 120,
      },
      {
        title: "Version",
        dataIndex: "eventId",
        key: "eventId.version",
        render: (eventId) => <Text>{eventId?.version}</Text>,
        width: 80,
      },
      {
        title: "Aggregate ID",
        dataIndex: "eventId",
        key: "eventId.aggregateId.aggregateId",
        render: (eventId) => (
          <Text ellipsis={true} copyable>
            {eventId?.aggregateId.aggregateId}
          </Text>
        ),
        width: 140,
      },
      {
        title: "Context",
        dataIndex: "eventId",
        key: "eventId.aggregateId.contextName",
        render: (eventId) => <Text>{eventId?.aggregateId.contextName}</Text>,
        width: 120,
      },
      {
        title: "Aggregate",
        dataIndex: "eventId",
        key: "eventId.aggregateId.aggregateName",
        render: (eventId) => <Text>{eventId?.aggregateId.aggregateName}</Text>,
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
        width: 125,
        fixed: "right",
      },
      {
        title: "Next Retry",
        dataIndex: "retryState",
        key: "retryState.nextRetryAt",
        render: (retryState) => (
          <Timer
            type="countdown"
            value={retryState?.nextRetryAt}
            valueStyle={{ fontSize: "14px" }}
          />
        ),
        width: 120,
        fixed: "right",
      },
    ],
  },
];
export function FailedTable({
  onPaginationChange,
  pagedList,
}: FailedTableProps) {
  const [selectedRecord, setSelectedRecord] = useState<ExecutionFailedState | null>(null);
  const [drawerVisible, setDrawerVisible] = useState(false);

  const showDrawer = (record: ExecutionFailedState) => {
    setSelectedRecord(record);
    setDrawerVisible(true);
  };

  const closeDrawer = () => {
    setDrawerVisible(false);
    setSelectedRecord(null);
  };

  return (
    <>
      <Table<ExecutionFailedState>
        rowKey="id"
        onRow={(record) => {
          return {
            onClick: (_event) => {
              showDrawer(record);
            },
          };
        }}
        columns={columns}
        dataSource={pagedList.list}
        pagination={{
          total: pagedList.total,
          onChange: onPaginationChange,
        }}
        bordered
        scroll={{ x: 1500 }}
      ></Table>
      <Drawer
        title="Failed Event Details"
        width={'60vw'}
        onClose={closeDrawer}
        open={drawerVisible}
        bodyStyle={{ paddingBottom: 80 }}
      >
        {selectedRecord && <FailedDetails state={selectedRecord} />}
      </Drawer>
    </>
  );
}
