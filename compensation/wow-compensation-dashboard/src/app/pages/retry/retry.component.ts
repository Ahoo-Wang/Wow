import {Component, OnInit} from '@angular/core';
import {NzCellFixedDirective, NzTableModule} from "ng-zorro-antd/table";
import {ExecutionFailedState} from "../../api/ExecutionFailedState";
import {DatePipe, NgForOf} from "@angular/common";
import {CompensationClient} from "../../api/CompensationClient";
import {NzMessageService} from "ng-zorro-antd/message";
import {NzButtonComponent, NzButtonGroupComponent} from "ng-zorro-antd/button";
import {NzPopconfirmDirective} from "ng-zorro-antd/popconfirm";
import {NzDrawerComponent, NzDrawerContentDirective} from "ng-zorro-antd/drawer";
import {NzTypographyComponent} from "ng-zorro-antd/typography";

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
  data: ExecutionFailedState[] = [];
  current: ExecutionFailedState | undefined;
  stackTraceVisible = false

  constructor(private compensationClient: CompensationClient, private message: NzMessageService) {
  }

  ngOnInit() {
    this.load();
  }

  load() {
    this.compensationClient.scan().subscribe(resp => {
        this.data = resp;
      }
    )
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
