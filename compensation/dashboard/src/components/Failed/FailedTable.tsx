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

import { Table, Typography, Statistic, Drawer, Button } from "antd";
import type { TableColumnsType } from "antd";
import type { PagedList } from "@ahoo-wang/fetcher-wow";
import type {
  EventId,
  ExecutionFailedState,
} from "../../services";
import { FailedDetails } from "./details/FailedDetails.tsx";
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
          <Text copyable ellipsis={{ tooltip: func.contextName }}>
            {func.contextName}
          </Text>
        ),
        width: 120,
      },
      {
        title: "Processor",
        dataIndex: "function",
        key: "function.processorName",
        render: (func) => (
          <Text copyable ellipsis={{ tooltip: func.processorName }}>
            {func.processorName}
          </Text>
        ),
        width: 120,
      },
      {
        title: "Name",
        dataIndex: "function",
        key: "function.name",
        render: (func) => (
          <Text copyable ellipsis={{ tooltip: func.name }}>
            {func.name}
          </Text>
        ),
        width: 150,
      },
      {
        title: "Kind",
        dataIndex: "function",
        key: "function.functionKind",
        render: (func) => <Text>{func.functionKind}</Text>,
        width: 80,
      },
    ],
  },
  {
    title: "Event Info",
    children: [
      {
        title: "Context",
        dataIndex: "eventId",
        key: "eventId.aggregateId.contextName",
        render: (eventId) => <Text>{eventId.aggregateId.contextName}</Text>,
        width: 120,
      },
      {
        title: "Aggregate",
        dataIndex: "eventId",
        key: "eventId.aggregateId.aggregateName",
        render: (eventId) => <Text>{eventId.aggregateId.aggregateName}</Text>,
        width: 120,
      },
      {
        title: "Aggregate ID",
        dataIndex: "eventId",
        key: "eventId.aggregateId.aggregateId",
        render: (eventId: EventId) => (
          <Text
            ellipsis={{ tooltip: eventId.aggregateId.aggregateId }}
            copyable
          >
            {eventId.aggregateId.aggregateId}
          </Text>
        ),
        width: 140,
      },
      {
        title: "Event ID",
        dataIndex: "eventId",
        key: "eventId.id",
        render: (eventId) => (
          <Text ellipsis={{ tooltip: eventId.id }} copyable>
            {eventId.id}
          </Text>
        ),
        width: 120,
      },
      {
        title: "Version",
        dataIndex: "eventId",
        key: "eventId.version",
        render: (eventId) => <Text>{eventId.version}</Text>,
        width: 80,
      },
    ],
  },
  {
    title: "Retries",
    key: "retries",
    render: (state: ExecutionFailedState) => {
      return (
        <Text>
          {state.retryState.retries}({state.retrySpec.maxRetries})
        </Text>
      );
    },
    width: 80,
    responsive: ['lg'],
  },
  {
    title: "Recoverable",
    dataIndex: "recoverable",
    key: "recoverable",
    width: 120,
  },
  {
    title: "Time",
    children: [
      {
        title: "Execute At",
        dataIndex: "executeAt",
        key: "executeAt",
        fixed: "right",
        render: (executeAt) =>
          executeAt && new Date(executeAt).toLocaleString(),
        width: 125,
      },
      {
        title: "Retry At",
        dataIndex: ["retryState", "retryAt"],
        key: "retryAt",
        fixed: "right",
        render: (retryAt) => new Date(retryAt).toLocaleString(),
        width: 125,
      },
      {
        title: "Next Retry",
        dataIndex: "retryState",
        key: "retryState.nextRetryAt",
        fixed: "right",
        render: (retryState) => (
          <Timer
            type="countdown"
            value={retryState.nextRetryAt}
            valueStyle={{ fontSize: "14px",color: "red" }}
          />
        ),
        width: 120,
      },
    ],
  },
  {
    title: "Actions",
    key: "actions",
    fixed: "right",
    width: 100,
    render: (_state: ExecutionFailedState) => (
      <>
        <Button type={"primary"}>Details</Button>
      </>
    ),
  },
];
export function FailedTable({
  onPaginationChange,
  pagedList,
}: FailedTableProps) {
  const [selectedRecord, setSelectedRecord] =
    useState<ExecutionFailedState | null>(null);
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
        size="small"
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
        title="Execution Failed Details"
        width={"80vw"}
        onClose={closeDrawer}
        open={drawerVisible}
      >
        {selectedRecord && <FailedDetails state={selectedRecord} />}
      </Drawer>
    </>
  );
}
