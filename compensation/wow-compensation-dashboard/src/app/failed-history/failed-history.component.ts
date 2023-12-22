import {Component, Input, OnInit} from '@angular/core';
import {CompensationClient} from "../api/CompensationClient";
import {DomainEventStream} from "../api/DomainEventStream";
import {NzTableCellDirective, NzTableModule} from "ng-zorro-antd/table";
import {DatePipe, NgForOf} from "@angular/common";
import {NzTypographyComponent} from "ng-zorro-antd/typography";

@Component({
  selector: 'app-failed-history',
  standalone: true,
    imports: [
        NzTableModule,
        NgForOf,
        DatePipe,
        NzTableCellDirective,
        NzTypographyComponent
    ],
  templateUrl: './failed-history.component.html',
  styleUrl: './failed-history.component.scss'
})
export class FailedHistoryComponent implements OnInit {
  @Input({required: true}) id!: string;
  eventStreams: DomainEventStream[] = [];

  constructor(private compensationClient: CompensationClient) {
  }

  ngOnInit() {
    this.load()
  }

  load() {
    this.compensationClient.loadEventStream(this.id, 1, 100).subscribe(resp => {
      this.eventStreams = resp
    })
  }
}
