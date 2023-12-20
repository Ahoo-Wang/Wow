import {Component, OnInit} from '@angular/core';
import {NzCellFixedDirective, NzTableModule, NzTableQueryParams} from "ng-zorro-antd/table";
import {ExecutionFailedState} from "../../api/ExecutionFailedState";
import {DatePipe, NgForOf} from "@angular/common";
import {CompensationClient} from "../../api/CompensationClient";
import {NzMessageService} from "ng-zorro-antd/message";
import {NzButtonComponent, NzButtonGroupComponent} from "ng-zorro-antd/button";
import {NzPopconfirmDirective} from "ng-zorro-antd/popconfirm";
import {NzDrawerComponent, NzDrawerContentDirective} from "ng-zorro-antd/drawer";
import {NzTypographyComponent} from "ng-zorro-antd/typography";
import {initialPagedQuery, PagedQuery, SortOrder} from "../../api/PagedQuery";
import {PagedList} from "../../api/PagedList";
import {NzTabComponent, NzTabSetComponent} from "ng-zorro-antd/tabs";
import {NzDescriptionsComponent, NzDescriptionsItemComponent} from "ng-zorro-antd/descriptions";
import {NzBadgeComponent} from "ng-zorro-antd/badge";
import {NzCountdownComponent} from "ng-zorro-antd/statistic";

@Component({
  selector: 'app-retry',
  standalone: true,
  templateUrl: './retry.component.html',
  imports: [
    NzTableModule,
    NzCellFixedDirective,
    NgForOf,
    NzButtonGroupComponent,
    NzPopconfirmDirective,
    NzButtonComponent,
    DatePipe,
    NzDrawerComponent,
    NzDrawerContentDirective,
    NzTypographyComponent,
    NzTabSetComponent,
    NzTabComponent,
    NzDescriptionsComponent,
    NzDescriptionsItemComponent,
    NzBadgeComponent,
    NzCountdownComponent
  ],
  styleUrls: ['./retry.component.scss']
})
export class RetryComponent implements OnInit {
  pagedQuery: PagedQuery = initialPagedQuery;
  pagedList: PagedList<ExecutionFailedState> = {total: 0, list: []};

  current: ExecutionFailedState | undefined;
  stackTraceVisible = false

  constructor(private compensationClient: CompensationClient, private message: NzMessageService) {
  }

  ngOnInit() {

  }

  load() {
    this.compensationClient.findAll(this.pagedQuery).subscribe(resp => {
        this.pagedList = resp
      }
    )
  }

  onQueryParamsChange(params: NzTableQueryParams): void {
    console.log(params);
    let sort = params.sort
      .filter(sort => sort.value != null)
      .map(sort => {
        return {field: sort.key, order: sort.value === "ascend" ? SortOrder.ASC : SortOrder.DESC}
      });
    if (sort.length == 0) {
      sort = initialPagedQuery.sort
    }
    this.pagedQuery = {sort: sort, pageIndex: params.pageIndex, pageSize: params.pageSize}
    this.load()
  }

  prepare(id: string): void {
    this.compensationClient.prepare(id)
      .subscribe(resp => {
        this.message.success("Prepare succeeded.");
        this.load();
      }, error => {
        this.message.error(error.error.errorMsg);
      })
  }

  openStackTrace(state: ExecutionFailedState) {
    this.current = state;
    this.stackTraceVisible = true
  }

  closeStackTrace() {
    this.stackTraceVisible = false
  }
}
