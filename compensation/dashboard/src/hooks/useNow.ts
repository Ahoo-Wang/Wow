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

import { useEffect, useState } from "react";

export function useNow(interval = 1_000): number {
  const [now, setNow] = useState(Date.now);

  useEffect(() => {
    let timer: number | undefined;
    const stop = () => {
      if (timer !== undefined) {
        window.clearInterval(timer);
        timer = undefined;
      }
    };
    const synchronize = () => {
      stop();
      if (document.visibilityState !== "visible") {
        return;
      }
      setNow(Date.now());
      timer = window.setInterval(() => setNow(Date.now()), interval);
    };

    synchronize();
    document.addEventListener("visibilitychange", synchronize);
    return () => {
      stop();
      document.removeEventListener("visibilitychange", synchronize);
    };
  }, [interval]);

  return now;
}
