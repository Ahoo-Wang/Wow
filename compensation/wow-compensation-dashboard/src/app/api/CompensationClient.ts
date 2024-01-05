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
import {HttpClient, HttpHeaders} from "@angular/common/http";
import {Observable} from "rxjs";
import {ExecutionFailedState} from "./ExecutionFailedState";
import {CommandResult, Stage} from "./CommandResult";
import {ApplyRetrySpec} from "./ApplyRetrySpec";
import {PagedQuery} from "./PagedQuery";
import {PagedList} from "./PagedList";
import {DomainEventStream} from "./DomainEventStream";
import {MarkRecoverable} from "./MarkRecoverable";

export enum FindCategory {
  ALL = 'all',
  TO_RETRY = 'to-retry',
  EXECUTING = 'executing',
  NEXT_RETRY = 'next-retry',
  NON_RETRYABLE = 'non-retryable',
  SUCCESS = 'success',
  UNRECOVERABLE = 'unrecoverable',
}

const COMMAND_HEADERS = {
  headers: {
    'Command-Wait-Stage': Stage.Snapshot
  }
}

@Injectable({providedIn: 'root'})
export class CompensationClient {
  aggregateName = 'execution_failed';
  commandApi = environment.host + `/${this.aggregateName}`;
  retryApi = environment.host + '/failed';

  constructor(private httpClient: HttpClient) {

  }

  prepare(id: string): Observable<CommandResult> {
    const apiUrl = `${this.commandApi}/${id}/prepare_compensation`;
    return this.httpClient.put<CommandResult>(apiUrl, {}, COMMAND_HEADERS);
  }

  forcePrepare(id: string): Observable<CommandResult> {
    const apiUrl = `${this.commandApi}/${id}/force_prepare_compensation`;
    return this.httpClient.put<CommandResult>(apiUrl, {}, COMMAND_HEADERS);
  }

  applyRetrySpec(id: string, appRetrySpec: ApplyRetrySpec): Observable<CommandResult> {
    const apiUrl = `${this.commandApi}/${id}/apply_retry_spec`;
    return this.httpClient.put<CommandResult>(apiUrl, appRetrySpec, COMMAND_HEADERS);
  }

  markRecoverable(id: string, markRecoverable: MarkRecoverable): Observable<CommandResult> {
    const apiUrl = `${this.commandApi}/${id}/mark_recoverable`;
    return this.httpClient.put<CommandResult>(apiUrl, markRecoverable, COMMAND_HEADERS);
  }

  find(category: FindCategory, pagedQuery: PagedQuery): Observable<PagedList<ExecutionFailedState>> {
    const apiUrl = `${this.retryApi}/${category}`;
    return this.httpClient.post<PagedList<ExecutionFailedState>>(apiUrl, pagedQuery);
  }

  findAll(pagedQuery: PagedQuery): Observable<PagedList<ExecutionFailedState>> {
    return this.find(FindCategory.ALL, pagedQuery);
  }

  findNextRetry(pagedQuery: PagedQuery): Observable<PagedList<ExecutionFailedState>> {
    return this.find(FindCategory.NEXT_RETRY, pagedQuery);
  }

  findToRetry(pagedQuery: PagedQuery): Observable<PagedList<ExecutionFailedState>> {
    return this.find(FindCategory.TO_RETRY, pagedQuery);
  }

  findNonRetryable(pagedQuery: PagedQuery): Observable<PagedList<ExecutionFailedState>> {
    return this.find(FindCategory.NON_RETRYABLE, pagedQuery);
  }

  findSuccess(pagedQuery: PagedQuery): Observable<PagedList<ExecutionFailedState>> {
    return this.find(FindCategory.SUCCESS, pagedQuery);
  }

  findUnrecoverable(pagedQuery: PagedQuery): Observable<PagedList<ExecutionFailedState>> {
    return this.find(FindCategory.UNRECOVERABLE, pagedQuery);
  }

  loadEventStream(id: string, headVersion: number = 1, tailVersion: number = 2147483647): Observable<DomainEventStream[]> {
    return this.httpClient.get<DomainEventStream[]>(`${this.commandApi}/${id}/event/${headVersion}/${tailVersion}`)
  }

  loadState(id: string): Observable<ExecutionFailedState> {
    return this.httpClient.get<ExecutionFailedState>(`${this.commandApi}/${id}/state`)
  }
}
