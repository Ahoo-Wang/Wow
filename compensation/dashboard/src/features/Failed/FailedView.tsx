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
  all,
  and,
  type Condition,
  desc,
  pagedList,
  type PagedList,
  pagedQuery,
  pagination,
  SnapshotMetadataFields,
} from "@ahoo-wang/fetcher-wow";
import {
  executionFailedSnapshotQueryClient,
  type ExecutionFailedState,
} from "../../services";
import { useEffect, useState } from "react";
import type { Pagination } from "@ahoo-wang/fetcher-wow";
import { useQueryParams } from "../../utils/useQuery.ts";
import { useGlobalDrawer } from "../../components/GlobalDrawer";
import { FetchingFailedDetails } from "./details/FetchingFailedDetails.tsx";

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
  }, [queryIdParams]);
  const [searchCondition, setSearchCondition] = useState<Condition>(all());
  const [searchPagination, setSearchPagination] = useState<Pagination>(() => {
    return pagination();
  });
  const [pagedResult, setPagedResult] =
    useState<PagedList<ExecutionFailedState>>(pagedList());
  const [refreshCount, setRefreshCount] = useState(0);
  const refresh = () => {
    setRefreshCount((count) => count + 1);
  };
  const onSearch = (searchCondition: Condition) => {
    setSearchCondition(searchCondition);
  };
  const onPaginationChange = (page: number, pageSize: number) => {
    setSearchPagination({
      index: page,
      size: pageSize,
    });
  };
  const search = () => {
    const query = pagedQuery({
      condition: and(
        RetryConditions.categoryToCondition(category),
        searchCondition,
      ),
      pagination: searchPagination,
      sort: [desc(SnapshotMetadataFields.FIRST_EVENT_TIME)],
    });
    executionFailedSnapshotQueryClient
      .pagedState<ExecutionFailedState>(query)
      .then((it) => {
        setPagedResult(it);
      });
  };
  useEffect(search, [
    category,
    searchCondition,
    searchPagination,
    refreshCount,
  ]);

  return (
    <>
      <FailedSearch onSearch={onSearch}></FailedSearch>
      <FailedTable
        pagedList={pagedResult}
        onPaginationChange={onPaginationChange}
        onChanged={refresh}
      ></FailedTable>
    </>
  );
}
