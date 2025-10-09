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
import { useQueryParams } from "../../utils/useQuery.ts";
import { useGlobalDrawer } from "../../components/GlobalDrawer";
import { FetchingFailedDetails } from "./details/FetchingFailedDetails.tsx";
import { executionFailedSnapshotQueryClient } from "../../services";
import { usePagedQuery } from "@ahoo-wang/fetcher-react";

interface FailedViewProps {
  category: FindCategory;
}

export default function FailedView({ category }: FailedViewProps) {
  const { openDrawer } = useGlobalDrawer();
  const queryIdParams = useQueryParams("id");

  useEffect(() => {
    if (!queryIdParams) {
      return;
    }
    openDrawer({
      title: "Execution Failed Details",
      children: <FetchingFailedDetails id={queryIdParams as string} />,
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
      execute();
    },
    [setQuery, execute, category],
  );
  const onPaginationChange = useCallback(
    (page: number, pageSize: number) => {
      getQuery().pagination = {
        index: page,
        size: pageSize,
      };
      execute();
    },
    [execute, getQuery],
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
