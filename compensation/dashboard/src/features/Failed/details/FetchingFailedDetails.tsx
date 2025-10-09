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

import {
  ExecutionFailedAggregatedFields,
  type ExecutionFailedState,
} from "../../../generated";
import { aggregateId, singleQuery } from "@ahoo-wang/fetcher-wow";
import { Flex, Skeleton, Typography } from "antd";
const { Text } = Typography;
import { FailedDetails } from "./FailedDetails.tsx";
import { executionFailedSnapshotQueryClient } from "../../../services";
import { useSingleQuery } from "@ahoo-wang/fetcher-react";
import { FetcherError } from "@ahoo-wang/fetcher";

export interface FetchingFailedDetailsProps {
  id: string;
}

export function FetchingFailedDetails({ id }: FetchingFailedDetailsProps) {
  const singleQueryState = useSingleQuery<
    ExecutionFailedState,
    ExecutionFailedAggregatedFields,
    FetcherError
  >({
    initialQuery: singleQuery({ condition: aggregateId(id) }),
    execute: executionFailedSnapshotQueryClient.singleState.bind(
      executionFailedSnapshotQueryClient,
    ),
    autoExecute: true,
  });
  if (singleQueryState.error) {
    return (
      <Flex justify="center" align="center" style={{ minHeight: 100 }}>
        <Text type="danger">
          Failed to load data: {singleQueryState.error.message}
        </Text>
      </Flex>
    );
  }

  if (!singleQueryState.result) {
    return (
      <Flex gap="small" vertical>
        <Skeleton active />
        <Skeleton active />
        <Skeleton active />
      </Flex>
    );
  }
  return <FailedDetails state={singleQueryState.result} />;
}
