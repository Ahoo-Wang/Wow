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

import { Navigate, useLocation } from "react-router";
import { executionsHref } from "@/features/Executions/linkScope.ts";

/**
 * An old queue address, sent on to its system view on the failed
 * executions' page with every parameter it came with — the open execution
 * (`id`), a failure cluster (`cluster`), an execution window (`start`,
 * `end`) — and replaced in the history, so going back skips it.
 */
export function QueueRedirect({ view }: { view: string }) {
  const { search, hash } = useLocation();
  return (
    <Navigate
      to={`${executionsHref(view, new URLSearchParams(search))}${hash}`}
      replace
    />
  );
}
