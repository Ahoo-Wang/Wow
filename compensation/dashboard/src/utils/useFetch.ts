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

import { useState, useEffect } from "react";

interface UseFetchResponse<DATA> {
  data: DATA | undefined;
  loading: boolean;
  error: Error | any | null;
  refetch: () => void;
}

export function useFetch<DATA>(
  fetcher: () => Promise<DATA>,
  deps: any[] = []
): UseFetchResponse<DATA> {
  const [data, setData] = useState<DATA>();
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<any>();
  const [retry, setRetry] = useState<number>(0);

  const refetch = () => setRetry(prev => prev + 1);

  useEffect(() => {
    let isCancelled = false;

    const fetchData = async () => {
      try {
        setLoading(true);
        setError(null);
        const result = await fetcher();

        if (!isCancelled) {
          setData(result);
        }
      } catch (err) {
        if (!isCancelled) {
          setError(err);
        }
      } finally {
        if (!isCancelled) {
          setLoading(false);
        }
      }
    };

    fetchData();

    return () => {
      isCancelled = true;
    };
  }, [...deps, retry]);

  return { data, loading, error, refetch };
}

