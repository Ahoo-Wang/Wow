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

import { type Condition } from "@ahoo-wang/fetcher-wow";

import { FilterPanel, FilterPanelProps } from "@ahoo-wang/fetcher-viewer";

interface FailedSearchProps {
  onSearch?: (condition: Condition) => void;
  loading?: boolean;
}

const filterPanelProps: FilterPanelProps = {
  filters: [
    {
      key: "_id",
      type: "id",
      field: {
        name: "_id",
        type: "id",
        label: "Id",
      },
    },
    {
      key: "state.eventId.id",
      type: "text",
      field: {
        name: "state.eventId.id",
        label: "EventId",
      },
    },
    {
      key: "state.eventId.aggregateId.aggregateId",
      type: "text",
      field: {
        name: "state.eventId.aggregateId.aggregateId",
        label: "AggregateId",
      },
    },
    {
      key: "state.eventId.aggregateId.contextName",
      type: "text",
      field: {
        name: "state.eventId.aggregateId.contextName",
        label: "AggregateContext",
      },
    },
    {
      key: "state.eventId.aggregateId.aggregateName",
      type: "text",
      field: {
        name: "state.eventId.aggregateId.aggregateName",
        label: "AggregateName",
      },
    },
    {
      key: "state.function.contextName",
      type: "text",
      field: {
        name: "state.function.contextName",
        label: "ProcessorContext",
      },
    },
    {
      key: "state.function.processorName",
      type: "text",
      field: {
        name: "state.function.processorName",
        label: "ProcessorName",
      },
    },
  ],
};

export function FailedSearch({ onSearch, loading }: FailedSearchProps) {
  return (
    <FilterPanel
      {...filterPanelProps}
      onSearch={onSearch}
      loading={loading}
      colSpan={6}
      style={{ marginBottom: "16px" }}
    />
  );
}
