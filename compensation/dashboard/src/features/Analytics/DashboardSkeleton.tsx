/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)]
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

import { Skeleton } from "@/components/ui/skeleton";
import { Card, CardContent, CardHeader } from "@/components/ui/card";

export default function DashboardSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading dashboard"
      className="dashboard-view dashboard-skeleton"
    >
      <div className="dashboard-toolbar">
        <Skeleton className="h-8 w-64 max-w-full" />
        <Skeleton className="h-8 w-24" />
      </div>
      <Card size="sm" className="dashboard-overview">
        <CardHeader className="sr-only">
          <span>Loading compensation overview</span>
        </CardHeader>
        <CardContent className="dashboard-overview-content">
          <section className="dashboard-stock">
            <Skeleton className="h-5 w-44 max-w-full" />
            <Skeleton className="h-36 w-full" />
          </section>
          <section className="dashboard-flow">
            <Skeleton className="h-5 w-56 max-w-full" />
            <Skeleton className="h-36 w-full" />
          </section>
        </CardContent>
      </Card>
      <Card size="sm" className="dashboard-activity">
        <CardHeader className="sr-only">
          <span>Loading dashboard activity</span>
        </CardHeader>
        <CardContent>
          <Skeleton className="h-56 w-full" />
        </CardContent>
      </Card>
      <Card size="sm" className="dashboard-health">
        <CardHeader>
          <Skeleton className="h-5 w-64 max-w-full" />
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3">
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-24 w-full" />
        </CardContent>
      </Card>
      <Card size="sm" className="dashboard-pressure">
        <CardHeader>
          <Skeleton className="h-6 w-72 max-w-full" />
        </CardHeader>
        <CardContent className="grid gap-2">
          {Array.from({ length: 2 }, (_, index) => (
            <Skeleton key={index} className="h-8 w-full" />
          ))}
        </CardContent>
      </Card>
    </div>
  );
}
