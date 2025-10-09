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

import { FailedSearch } from "./FailedSearch.tsx";
import { FailedTable } from "./FailedTable.tsx";
import { type FindCategory, RetryConditions } from "./FindCategory.ts";
import {
  and,
  type Condition,
  pagedList,
  pagedQuery,
} from "@ahoo-wang/fetcher-wow";
import { type ExecutionFailedState } from "../../generated";
import { useCallback, useEffect } from "react";
import { useQueryParams } from "../../utils/useQueryParams.ts";
import { useGlobalDrawer } from "../../components/GlobalDrawer";
import { FetchingFailedDetails } from "./details/FetchingFailedDetails.tsx";
import { executionFailedSnapshotQueryClient } from "../../services";
import { usePagedQuery } from "@ahoo-wang/fetcher-react";
import { App } from "antd";

interface FailedViewProps {
  category: FindCategory;
}

export default function FailedView({ category }: FailedViewProps) {
  const { notification } = App.useApp();
  const { openDrawer } = useGlobalDrawer();
  const queryIdParams = useQueryParams("id");

  useEffect(() => {
    if (!queryIdParams) {
      return;
    }
    const queryId = queryIdParams as string;
    openDrawer({
      title: "Execution Failed Details",
      children: <FetchingFailedDetails key={queryId} id={queryId} />,
    });
  }, [queryIdParams, openDrawer]);

  const { loading, result, getQuery, setQuery, execute } =
    usePagedQuery<ExecutionFailedState>({
      initialQuery: pagedQuery({
        condition: RetryConditions.categoryToCondition(category),
      }),
      execute: executionFailedSnapshotQueryClient.pagedState.bind(
        executionFailedSnapshotQueryClient,
      ),
      autoExecute: true,
      onError: (error) => {
        notification.error({
          message: "Search Error",
          description: error.message,
        });
      },
    });

  const onSearch = useCallback(
    (searchCondition: Condition) => {
      setQuery(
        pagedQuery({
          condition: and(
            RetryConditions.categoryToCondition(category),
            searchCondition,
          ),
        }),
      );
    },
    [setQuery, category],
  );
  const onPaginationChange = useCallback(
    (page: number, pageSize: number) => {
      setQuery({ ...getQuery(), pagination: { index: page, size: pageSize } });
    },
    [getQuery, setQuery],
  );
  const onRefresh = useCallback(() => {
    execute();
  }, [execute]);

  return (
    <>
      <FailedSearch onSearch={onSearch}></FailedSearch>
      <FailedTable
        loading={loading}
        pagedList={result ?? pagedList()}
        onPaginationChange={onPaginationChange}
        onChanged={onRefresh}
      ></FailedTable>
    </>
  );
}
