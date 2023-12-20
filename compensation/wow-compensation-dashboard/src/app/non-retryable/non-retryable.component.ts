import {Component} from '@angular/core';
import {CompensationClient, FindCategory} from "../api/CompensationClient";
import {FailedListComponent} from "../failed-list/failed-list.component";

@Component({
  selector: 'app-non-retryable',
  standalone: true,
  imports: [
    FailedListComponent
  ],
  templateUrl: './non-retryable.component.html',
  styleUrl: './non-retryable.component.scss'
})
export class NonRetryableComponent {
  constructor(protected compensationClient: CompensationClient) {
  }

  protected readonly FindCategory = FindCategory;
}
