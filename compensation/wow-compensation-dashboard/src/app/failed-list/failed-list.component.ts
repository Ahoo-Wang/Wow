import {Component, Input, OnInit} from '@angular/core';
import {NzCellFixedDirective, NzTableModule, NzTableQueryParams} from "ng-zorro-antd/table";
import {ExecutionFailedState, ExecutionFailedStatus, RecoverableType, RetrySpec} from "../api/ExecutionFailedState";
import {DatePipe, NgForOf, NgIf} from "@angular/common";
import {CompensationClient, FindCategory} from "../api/CompensationClient";
import {NzMessageService} from "ng-zorro-antd/message";
import {NzButtonComponent, NzButtonGroupComponent} from "ng-zorro-antd/button";
import {NzPopconfirmDirective} from "ng-zorro-antd/popconfirm";
import {NzDrawerComponent, NzDrawerContentDirective, NzDrawerModule, NzDrawerService} from "ng-zorro-antd/drawer";
import {NzTypographyComponent} from "ng-zorro-antd/typography";
import {initialPagedQuery, PagedQuery, SortOrder} from "../api/PagedQuery";
import {PagedList} from "../api/PagedList";
import {NzBadgeComponent} from "ng-zorro-antd/badge";
import {NzCountdownComponent} from "ng-zorro-antd/statistic";
import {ErrorComponent} from "../error/error.component";
import {FailedHistoryComponent} from "../failed-history/failed-history.component";
import {NzIconModule} from 'ng-zorro-antd/icon';
import {ApplyRetrySpecComponent} from "../apply-retry-spec/apply-retry-spec.component";
import {ActivatedRoute} from "@angular/router";
import {NzSelectModule} from 'ng-zorro-antd/select';
import {NzToolTipModule} from 'ng-zorro-antd/tooltip';
import {NzDropDownModule} from 'ng-zorro-antd/dropdown';
import {FormsModule} from "@angular/forms";


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
    FailedHistoryComponent,
    NzIconModule,
    NzDrawerModule,
    NzSelectModule,
    NzToolTipModule,
    NzDropDownModule,
    FormsModule
  ],
  styleUrls: ['./failed-list.component.scss']
})
export class FailedListComponent implements OnInit {
  pagedQuery: PagedQuery = initialPagedQuery;
  pagedList: PagedList<ExecutionFailedState> = {total: 0, list: []};
  @Input({required: true}) category: FindCategory = FindCategory.TO_RETRY;
  current: ExecutionFailedState | undefined;
  errorInfoVisible = false
  expandSet = new Set<string>();

  constructor(private compensationClient: CompensationClient,
              private message: NzMessageService,
              private drawerService: NzDrawerService,
              private activatedRoute: ActivatedRoute) {
  }

  ngOnInit() {
    this.showErrorInfoIfId()
  }

  onExpandChange(id: string, checked: boolean): void {
    if (checked) {
      this.expandSet.add(id);
    } else {
      this.expandSet.delete(id);
    }
  }

  load() {
    this.compensationClient.find(this.category, this.pagedQuery).subscribe(resp => {
        this.pagedList = resp
      }
    )
  }

  showErrorInfoIfId() {
    const id = this.activatedRoute.snapshot.queryParams['id']
    if (!id) {
      return
    }
    this.compensationClient.loadState(id).subscribe(resp => {
      this.openErrorInfo(resp)
    })
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

  markRecoverable(id: string, recoverable: RecoverableType): void {
    this.compensationClient.markRecoverable(id, {recoverable})
      .subscribe(resp => {
        this.message.success("Mark Recoverable succeeded.");
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

  editRetrySpec(id: string, retrySpec: RetrySpec) {
    const editRetrySpecModal = this.drawerService.create<ApplyRetrySpecComponent, {
      id: string,
      retrySpec: RetrySpec
    }>({
      nzTitle: `Apply Retry Spec`,
      nzWidth: '280px',
      nzContent: ApplyRetrySpecComponent,
      nzData: {
        id: id,
        retrySpec: {
          maxRetries: retrySpec.maxRetries,
          minBackoff: retrySpec.minBackoff,
          executionTimeout: retrySpec.executionTimeout
        }
      }
    });

    editRetrySpecModal.afterOpen.subscribe(() => {
      const instance = editRetrySpecModal.getContentComponent();
      instance!.afterApply.subscribe(result => {
        editRetrySpecModal.close()
        this.load()
      })
    });
  }

  protected readonly FindCategory = FindCategory;
  protected readonly ExecutionFailedStatus = ExecutionFailedStatus;
  protected readonly RecoverableType = RecoverableType;
}
