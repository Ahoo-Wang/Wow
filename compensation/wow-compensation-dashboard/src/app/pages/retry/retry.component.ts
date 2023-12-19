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
import {PagedQuery} from "../../api/PagedQuery";
import {PagedList} from "../../api/PagedList";

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
    NzTypographyComponent
  ],
  styleUrls: ['./retry.component.scss']
})
export class RetryComponent implements OnInit {
  pagedQuery: PagedQuery = {pageIndex: 1, pageSize: 10}
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
    this.pagedQuery = {pageIndex: params.pageIndex, pageSize: params.pageSize}
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
