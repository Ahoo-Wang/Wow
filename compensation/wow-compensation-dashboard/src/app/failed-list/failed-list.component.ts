import {Component, Input, OnInit} from '@angular/core';
import {NzCellFixedDirective, NzTableModule, NzTableQueryParams} from "ng-zorro-antd/table";
import {ExecutionFailedState, ExecutionFailedStatus} from "../api/ExecutionFailedState";
import {DatePipe, NgForOf, NgIf} from "@angular/common";
import {CompensationClient, FindCategory} from "../api/CompensationClient";
import {NzMessageService} from "ng-zorro-antd/message";
import {NzButtonComponent, NzButtonGroupComponent} from "ng-zorro-antd/button";
import {NzPopconfirmDirective} from "ng-zorro-antd/popconfirm";
import {NzDrawerComponent, NzDrawerContentDirective} from "ng-zorro-antd/drawer";
import {NzTypographyComponent} from "ng-zorro-antd/typography";
import {initialPagedQuery, PagedQuery, SortOrder} from "../api/PagedQuery";
import {PagedList} from "../api/PagedList";
import {NzBadgeComponent} from "ng-zorro-antd/badge";
import {NzCountdownComponent} from "ng-zorro-antd/statistic";
import {ErrorComponent} from "../error/error.component";

@Component({
  selector: 'app-failed-list',
  standalone: true,
  templateUrl: './failed-list.component.html',
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
    NzBadgeComponent,
    NzCountdownComponent,
    ErrorComponent,
    NgIf,
  ],
  styleUrls: ['./failed-list.component.scss']
})
export class FailedListComponent implements OnInit {
  pagedQuery: PagedQuery = initialPagedQuery;
  pagedList: PagedList<ExecutionFailedState> = {total: 0, list: []};
  @Input({required: true}) category: FindCategory = FindCategory.TO_RETRY;
  current: ExecutionFailedState | undefined;
  errorInfoVisible = false

  constructor(private compensationClient: CompensationClient,
              private message: NzMessageService) {
  }

  ngOnInit() {
  }

  load() {
    this.compensationClient.find(this.category, this.pagedQuery).subscribe(resp => {
        this.pagedList = resp
      }
    )
  }

  onQueryParamsChange(params: NzTableQueryParams): void {
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

  forcePrepare(id: string): void {
    this.compensationClient.forcePrepare(id)
      .subscribe(resp => {
        this.message.success("Force Prepare succeeded.");
        this.load();
      }, error => {
        this.message.error(error.error.errorMsg);
      })
  }

  openErrorInfo(state: ExecutionFailedState) {
    this.current = state;
    this.errorInfoVisible = true
  }

  closeErrorInfo() {
    this.errorInfoVisible = false
  }

  protected readonly FindCategory = FindCategory;
  protected readonly ExecutionFailedStatus = ExecutionFailedStatus;
}
