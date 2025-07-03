import {Component, Input, OnInit} from '@angular/core';
import {NzCellFixedDirective, NzTableModule, NzTableQueryParams} from "ng-zorro-antd/table";
import {
  ExecutionFailedState,
  ExecutionFailedStatus, RecoverableType,
  RetrySpec
} from "../api/ExecutionFailedState";
import {DatePipe, NgForOf, NgIf} from "@angular/common";
import {CompensationClient, FindCategory} from "../api/CompensationClient";
import {NzMessageService} from "ng-zorro-antd/message";
import {NzButtonModule} from 'ng-zorro-antd/button';
import {NzPopconfirmDirective} from "ng-zorro-antd/popconfirm";
import {NzDrawerComponent, NzDrawerContentDirective, NzDrawerModule, NzDrawerService} from "ng-zorro-antd/drawer";
import {NzTypographyComponent} from "ng-zorro-antd/typography";
import {initialSnapshotPagedQuery, PagedQuery} from "../api/PagedQuery";
import {PagedList} from "../api/PagedList";
import {NzCountdownComponent} from "ng-zorro-antd/statistic";
import {ErrorComponent} from "../error/error.component";
import {FailedHistoryComponent} from "../failed-history/failed-history.component";
import {NzIconModule} from 'ng-zorro-antd/icon';
import {ApplyRetrySpecComponent} from "../apply-retry-spec/apply-retry-spec.component";
import {ActivatedRoute} from "@angular/router";
import {NzSelectModule} from 'ng-zorro-antd/select';
import {NzToolTipModule} from 'ng-zorro-antd/tooltip';
import {NzDropDownModule} from 'ng-zorro-antd/dropdown';
import {FormControl, FormsModule, NonNullableFormBuilder, ReactiveFormsModule} from "@angular/forms";
import {Condition, Conditions, Projections, Sort, SortDirection} from "../api/Query";
import {NzInputModule} from 'ng-zorro-antd/input';
import {NzColDirective, NzRowDirective} from "ng-zorro-antd/grid";
import {NzFormControlComponent, NzFormDirective, NzFormItemComponent, NzFormLabelComponent} from "ng-zorro-antd/form";
import {NzDividerModule} from 'ng-zorro-antd/divider';
import {NzPopoverDirective} from "ng-zorro-antd/popover";
import {NzSpaceModule} from 'ng-zorro-antd/space';
import {NzFlexModule} from 'ng-zorro-antd/flex';

@Component({
  selector: 'app-failed-list',
  templateUrl: './failed-list.component.html',
  imports: [
    NzTableModule,
    NzCellFixedDirective,
    NzButtonModule,
    NzPopconfirmDirective,
    DatePipe,
    NzDrawerComponent,
    NzDrawerContentDirective,
    NzTypographyComponent,
    NzCountdownComponent,
    ErrorComponent,
    FailedHistoryComponent,
    NzIconModule,
    NzDrawerModule,
    NzSelectModule,
    NzToolTipModule,
    NzDropDownModule,
    FormsModule,
    NzInputModule,
    NzColDirective,
    NzFormControlComponent,
    NzFormDirective,
    NzFormItemComponent,
    NzFormLabelComponent,
    NzRowDirective,
    ReactiveFormsModule, NzDividerModule, NzPopoverDirective, NzSpaceModule, NzFlexModule, NgForOf, NgIf
  ],
  styleUrls: ['./failed-list.component.scss']
})
export class FailedListComponent implements OnInit {
  pagedQuery: PagedQuery = initialSnapshotPagedQuery;
  pagedList: PagedList<ExecutionFailedState> = {total: 0, list: []};
  @Input({required: true}) category: FindCategory = FindCategory.TO_RETRY;
  loading = false;
  current: ExecutionFailedState | undefined;
  errorInfoVisible = false
  expandSet = new Set<string>();
  validateForm = this.formBuilder.group({
    id: [''],
    eventId: [''],
    aggregateId: [''],
    aggregateContext: [''],
    aggregateName: [''],
    processorContext: [''],
    processorName: [''],
  })

  constructor(private compensationClient: CompensationClient,
              private message: NzMessageService,
              private drawerService: NzDrawerService,
              private activatedRoute: ActivatedRoute,
              private formBuilder: NonNullableFormBuilder) {
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

  controlToCondition(control: FormControl<string>, to: (value: string) => Condition): Condition | null {
    let value = control.value
    if (!value || value.length == 0) {
      return null
    }
    return to(value)
  }

  controlToEqCondition(control: FormControl<string>, field: string): Condition | null {
    return this.controlToCondition(control, value => Conditions.eq(field, value))
  }

  buildCondition() {
    let conditions: Condition[] = []
    let idCondition = this.controlToCondition(this.validateForm.controls.id, value => Conditions.id(value))
    if (idCondition) {
      conditions.push(idCondition)
    }
    let eventIdCondition = this.controlToEqCondition(this.validateForm.controls.eventId, "state.eventId.id")
    if (eventIdCondition) {
      conditions.push(eventIdCondition)
    }
    let aggregateIdCondition = this.controlToEqCondition(this.validateForm.controls.aggregateId, "state.eventId.aggregateId.aggregateId")
    if (aggregateIdCondition) {
      conditions.push(aggregateIdCondition)
    }
    let aggregateContextCondition = this.controlToEqCondition(this.validateForm.controls.aggregateContext, "state.eventId.aggregateId.contextName")
    if (aggregateContextCondition) {
      conditions.push(aggregateContextCondition)
    }
    let aggregateNameCondition = this.controlToEqCondition(this.validateForm.controls.aggregateName, "state.eventId.aggregateId.aggregateName")
    if (aggregateNameCondition) {
      conditions.push(aggregateNameCondition)
    }
    let processorContextCondition = this.controlToEqCondition(this.validateForm.controls.processorContext, "state.function.contextName")
    if (processorContextCondition) {
      conditions.push(processorContextCondition)
    }
    let processorNameCondition = this.controlToEqCondition(this.validateForm.controls.processorName, "state.function.processorName")
    if (processorNameCondition) {
      conditions.push(processorNameCondition)
    }
    if (conditions.length > 0) {
      this.pagedQuery.condition = Conditions.and(conditions)
    } else {
      this.pagedQuery.condition = Conditions.all()
    }
  }

  load() {
    this.loading = true
    this.buildCondition()
    this.compensationClient.find(this.category, this.pagedQuery).subscribe(resp => {
        this.pagedList = resp
        this.loading = false
      }
    )
  }

  reset() {
    this.validateForm.reset()
    this.load()
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
    let sort: Sort[] = params.sort
      .filter(sort => sort.value != null)
      .map(sort => {
        let direction = sort.value === "ascend" ? SortDirection.ASC : SortDirection.DESC
        return {field: sort.key, direction: direction}
      });
    if (sort.length == 0) {
      sort = initialSnapshotPagedQuery.sort
    }
    this.pagedQuery = {
      projection: Projections.all(),
      sort: sort,
      pagination: {index: params.pageIndex, size: params.pageSize},
      condition: initialSnapshotPagedQuery.condition
    }
    this.load()
  }

  prepare(id: string): void {
    this.compensationClient.prepare(id)
      .subscribe({
        next: (resp) => {
          this.message.success("Prepare succeeded.");
          this.load();
        }, error: (error) => {
          this.message.error(error.error.errorMsg);
        }
      })
  }

  forcePrepare(id: string): void {
    this.compensationClient.forcePrepare(id)
      .subscribe({
        next: (resp) => {
          this.message.success("Force Prepare succeeded.");
          this.load();
        }, error: (error) => {
          this.message.error(error.error.errorMsg);
        }
      })
  }

  markRecoverable(id: string, recoverable: RecoverableType): void {
    this.compensationClient.markRecoverable(id, {recoverable})
      .subscribe({
        next: (resp) => {
          this.message.success("Mark Recoverable succeeded.");
          this.load();
        },
        error: (error) => {
          this.message.error(error.error.errorMsg);
        }
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
