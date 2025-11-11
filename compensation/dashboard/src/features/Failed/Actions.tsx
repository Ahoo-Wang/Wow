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

import { CommandResult, StateCapable } from "@ahoo-wang/fetcher-wow";
import type { ExecutionFailedState } from "../../generated";
import { App, Dropdown } from "antd";
import { FailedDetails } from "./details/FailedDetails.tsx";
import { useGlobalDrawer } from "../../components/GlobalDrawer";
import type { ItemType } from "antd/es/menu/interface";
import { executionFailedCommandClient } from "../../services";
import { useExecutePromise } from "@ahoo-wang/fetcher-react";
import { ExchangeError } from "@ahoo-wang/fetcher";

export interface OnChangedCapable {
  onChanged?: () => void;
}

export interface ActionsProps
  extends StateCapable<ExecutionFailedState>,
    OnChangedCapable {}

export function Actions({ state, onChanged }: ActionsProps) {
  const { openDrawer } = useGlobalDrawer();
  const { notification } = App.useApp();
  const preparePromiseState = useExecutePromise<CommandResult, ExchangeError>({
    onSuccess: () => {
      notification.info({ message: "Prepare Success" });
      onChanged?.();
    },
    onError: async (error) => {
      const commandResult = await error.exchange.extractResult<CommandResult>();
      notification.error({
        message: "Prepare Failed",
        description: commandResult.errorMsg,
      });
    },
  });
  const forcePreparePromiseState = useExecutePromise<
    CommandResult,
    ExchangeError
  >({
    onSuccess: () => {
      notification.info({ message: "Force Prepare Success" });
      onChanged?.();
    },
    onError: async (error) => {
      const commandResult = await error.exchange.extractResult<CommandResult>();
      notification.error({
        message: "Force Prepare Failed",
        description: commandResult.errorMsg,
      });
    },
  });
  const items: ItemType[] = [
    {
      key: "prepare",
      label: "Prepare",
      onClick: () => {
        preparePromiseState.execute((abortController) =>
          executionFailedCommandClient.prepareCompensation(state.id, {
            abortController,
          }),
        );
      },
    },
    {
      key: "forcePrepare",
      label: "Force Prepare",
      onClick: () => {
        forcePreparePromiseState.execute((abortController) =>
          executionFailedCommandClient.forcePrepareCompensation(state.id, {
            abortController,
          }),
        );
      },
    },
  ];
  return (
    <>
      <Dropdown.Button
        size="small"
        type={"primary"}
        onClick={() =>
          openDrawer({
            title: "Execution Failed Details",
            children: <FailedDetails state={state} />,
          })
        }
        menu={{
          items,
        }}
      >
        Details
      </Dropdown.Button>
    </>
  );
}
