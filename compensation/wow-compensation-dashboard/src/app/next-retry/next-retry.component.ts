import { Component } from '@angular/core';
import {CompensationClient, FindCategory} from "../api/CompensationClient";
import {FailedListComponent} from "../failed-list/failed-list.component";

@Component({
  selector: 'app-next-retry',
  standalone: true,
  imports: [
    FailedListComponent
  ],
  templateUrl: './next-retry.component.html',
  styleUrl: './next-retry.component.scss'
})
export class NextRetryComponent {
  constructor(protected compensationClient: CompensationClient) {
  }

  protected readonly FindCategory = FindCategory;
}
