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

import type { StateCapable } from "@ahoo-wang/fetcher-wow";
import type { ExecutionFailedState } from "../../services";
import { App, Dropdown } from "antd";
import { FailedDetails } from "./details/FailedDetails.tsx";
import { useGlobalDrawer } from "../GlobalDrawer/GlobalDrawer.tsx";
import type { ItemType } from "antd/es/menu/interface";
import { executionFailedCommandService } from "../../services/executionFailedCommandClient.ts";

export interface OnChangedCapable {
  onChanged?: () => void;
}

export interface ActionsProps
  extends StateCapable<ExecutionFailedState>,
    OnChangedCapable {
}

export function Actions({ state, onChanged }: ActionsProps) {
  const { openDrawer } = useGlobalDrawer();
  const { notification } = App.useApp();
  const items: ItemType[] = [
    {
      key: "prepare",
      label: "Prepare",
      onClick: () => {
        executionFailedCommandService
          .prepare(state.id)
          .then(() => {
            notification.success({ message: "Prepare Success" });
            onChanged?.();
          })
          .catch((error) => {
            notification.error({
              message: "Prepare Failed",
              description: error.message,
            });
          });
      },
    },
    {
      key: "forcePrepare",
      label: "Force Prepare",
      onClick: () => {
        executionFailedCommandService
          .forcePrepare(state.id)
          .then(() => {
            notification.success({ message: "Force Prepare Success" });
            onChanged?.();
          })
          .catch((error) => {
            notification.error({
              message: "Force Prepare Failed",
              description: error.message,
            });
          });
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
            content: <FailedDetails state={state} />,
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
