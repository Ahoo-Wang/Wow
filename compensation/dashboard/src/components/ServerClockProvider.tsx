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

import { fetcher } from "@ahoo-wang/fetcher";
import { RefreshCw } from "lucide-react";
import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Button } from "@/components/ui/button";
import { serverNow, synchronizeServerClock } from "@/services/serverClock.ts";
import {
  ServerClockContext,
  type ServerClockContextValue,
} from "@/components/ServerClockContext.ts";

interface DashboardRuntime {
  serverTime: number;
}

const RESYNC_INTERVAL = 5 * 60_000;

export function ServerClockProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [error, setError] = useState<Error>();
  const abortControllerRef = useRef<AbortController | undefined>(undefined);

  const synchronize = useCallback(async () => {
    abortControllerRef.current?.abort();
    const abortController = new AbortController();
    abortControllerRef.current = abortController;
    try {
      const response = await fetcher.get("/dashboard/runtime", {
        abortController,
        cache: "no-store",
      });
      const runtime = (await response.json()) as DashboardRuntime;
      synchronizeServerClock(runtime.serverTime);
      setReady(true);
      setError(undefined);
    } catch (cause) {
      if (abortController.signal.aborted) {
        return;
      }
      setError(cause instanceof Error ? cause : new Error(String(cause)));
    }
  }, []);

  useEffect(() => {
    const initialSync = window.setTimeout(() => void synchronize(), 0);
    const refreshVisibleClock = () => {
      if (document.visibilityState === "visible") {
        void synchronize();
      }
    };
    const timer = window.setInterval(refreshVisibleClock, RESYNC_INTERVAL);
    document.addEventListener("visibilitychange", refreshVisibleClock);
    return () => {
      abortControllerRef.current?.abort();
      window.clearTimeout(initialSync);
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", refreshVisibleClock);
    };
  }, [synchronize]);

  const context = useMemo<ServerClockContextValue>(
    () => ({
      now: () => {
        const current = serverNow();
        if (current === undefined) {
          throw new Error("Server clock has not been synchronized.");
        }
        return current;
      },
    }),
    [],
  );

  if (!ready) {
    return (
      <div className="flex min-h-svh items-center justify-center bg-slate-50 p-6 text-center">
        <div role={error ? "alert" : "status"} className="max-w-sm">
          <p className="text-sm font-medium text-slate-800">
            {error ? "Unable to synchronize with the server" : "Connecting…"}
          </p>
          {error ? (
            <>
              <p className="mt-1 text-xs text-slate-500">{error.message}</p>
              <Button
                type="button"
                variant="outline"
                className="mt-4"
                onClick={() => void synchronize()}
              >
                <RefreshCw />
                Retry
              </Button>
            </>
          ) : null}
        </div>
      </div>
    );
  }

  return (
    <ServerClockContext.Provider value={context}>
      {children}
    </ServerClockContext.Provider>
  );
}
