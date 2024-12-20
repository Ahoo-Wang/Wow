import {Component, Input, OnInit} from '@angular/core';
import {CompensationClient} from "../api/CompensationClient";
import {EventStreamHistory} from "../api/DomainEventStream";
import {NzTableModule} from "ng-zorro-antd/table";
import {DatePipe, NgForOf} from "@angular/common";
import {NzTypographyComponent} from "ng-zorro-antd/typography";
import {Pagination} from "../api/Query";
import {MAX_VERSION} from "../api/EventStreamQuery";

@Component({
  selector: 'app-failed-history',
  imports: [
    NzTableModule,
    NgForOf,
    DatePipe,
    NzTypographyComponent
  ],
  templateUrl: './failed-history.component.html',
  styleUrl: './failed-history.component.scss'
})
export class FailedHistoryComponent implements OnInit {
  @Input({required: true}) id!: string;
  maxVersion = MAX_VERSION;
  pagination: Pagination = {index: 1, size: 10};
  eventStreams: EventStreamHistory[] = [];

  constructor(private compensationClient: CompensationClient) {
  }

  ngOnInit() {
  }

  load() {
    this.compensationClient.listHistory(this.id, this.maxVersion, this.pagination.index, this.pagination.size)
      .subscribe(resp => {
          if (this.pagination.index == 1 && resp.length > 0) {
            this.maxVersion = resp[0].version
          }
          this.eventStreams = resp
        }
      )
  }

}
