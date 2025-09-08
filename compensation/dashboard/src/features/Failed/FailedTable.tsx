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

import { Table, Tag, Typography, Statistic, Button } from "antd";
import type { TableColumnsType } from "antd";
import { type PagedList } from "@ahoo-wang/fetcher-wow";
import type { EventId, ExecutionFailedState } from "../../services";
import { useGlobalDrawer } from "../../components/GlobalDrawer";
import { EditTwoTone } from "@ant-design/icons";
import { ApplyRetrySpec } from "./ApplyRetrySpec.tsx";
import { useMemo } from "react";
import { ChangeFunction } from "./ChangeFunction.tsx";
import { MarkRecoverable } from "./MarkRecoverable.tsx";
import { Actions, type OnChangedCapable } from "./Actions.tsx";

const { Text } = Typography;
const { Timer } = Statistic;

interface FailedTableProps extends OnChangedCapable {
  onPaginationChange?: (page: number, pageSize: number) => void;
  pagedList: PagedList<ExecutionFailedState>;
}

export function FailedTable({
  onPaginationChange,
  pagedList,
  onChanged,
}: FailedTableProps) {
  const { openDrawer } = useGlobalDrawer();
  const dataColumns = useMemo<TableColumnsType<ExecutionFailedState>>(() => {
    return [
      {
        title: "ID",
        key: "id",
        dataIndex: "id",
        width: 100,
        fixed: "left",
        render: (id: string) => <Text copyable>{id}</Text>,
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
            key: "function.functionKind",
            render: (state: ExecutionFailedState) => {
              return (
                <Text>
                  {state.function.functionKind}
                  <Button
                    type="dashed"
                    shape="circle"
                    size="small"
                    icon={<EditTwoTone />}
                    onClick={() =>
                      openDrawer({
                        title: "Change Function",
                        width: "500px",
                        children: (
                          <ChangeFunction
                            id={state.id}
                            functionInfo={state.function}
                            onChanged={onChanged}
                          />
                        ),
                      })
                    }
                  ></Button>
                </Text>
              );
            },
            width: 100,
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
            render: (eventId) => (
              <Text>{eventId.aggregateId.aggregateName}</Text>
            ),
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
            key: "retryState",
            render: (state: ExecutionFailedState) => {
              return (
                <Text>
                  {state.retryState.retries}({state.retrySpec.maxRetries})
                  <Button
                    type="dashed"
                    shape="circle"
                    size="small"
                    icon={<EditTwoTone />}
                    aria-label="Edit retry specification"
                    onClick={() =>
                      openDrawer({
                        title: "Apply Retry Spec",
                        width: "300px",
                        children: (
                          <ApplyRetrySpec
                            id={state.id}
                            retrySpec={state.retrySpec}
                            onChanged={onChanged}
                          />
                        ),
                      })
                    }
                  ></Button>
                </Text>
              );
            },
            width: 80,
          },
          {
            title: "Recoverable",
            key: "recoverable",
            width: 120,
            render: (state: ExecutionFailedState) => {
              return (
                <MarkRecoverable
                  id={state.id}
                  recoverable={state.recoverable}
                  onChanged={onChanged}
                ></MarkRecoverable>
              );
            },
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
                value={retryState.nextRetryAt}
                valueStyle={{ fontSize: "14px" }}
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
        render: (state: ExecutionFailedState) => {
          return <Actions state={state} onChanged={onChanged}></Actions>;
        },
      },
    ];
  }, [openDrawer]);

  return (
    <>
      <Table<ExecutionFailedState>
        size="small"
        rowKey="id"
        columns={dataColumns}
        dataSource={pagedList.list}
        pagination={{
          total: pagedList.total,
          onChange: onPaginationChange,
        }}
        bordered
        scroll={{ x: 1500 }}
      ></Table>
    </>
  );
}
