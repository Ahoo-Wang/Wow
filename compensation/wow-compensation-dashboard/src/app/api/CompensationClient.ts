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
import {Injectable} from "@angular/core";
import {environment} from "../../environments/environment";
import {HttpClient} from "@angular/common/http";
import {Observable} from "rxjs";
import {ExecutionFailedState} from "./ExecutionFailedState";
import {CommandResult} from "./CommandResult";

@Injectable({providedIn: 'root'})
export class CompensationClient {
  apiPrefix = environment.host + '/execution_failed';

  constructor(private httpClient: HttpClient) {

  }

  scan(cursorId: string = "(0)", limit: number = 100): Observable<ExecutionFailedState[]> {
    const apiUrl = `${this.apiPrefix}/state/${cursorId}/${limit}`;
    return this.httpClient.get<ExecutionFailedState[]>(apiUrl);
  }

  prepare(id: string): Observable<CommandResult> {
    const apiUrl = `${this.apiPrefix}/${id}/prepare_compensation`;
    return this.httpClient.put<CommandResult>(apiUrl, {});
  }
}
