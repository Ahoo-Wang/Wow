import {Component} from '@angular/core';
import {FailedListComponent} from "../failed-list/failed-list.component";
import {CompensationClient, FindCategory} from "../api/CompensationClient";

@Component({
  selector: 'app-to-retry',
  standalone: true,
  imports: [
    FailedListComponent
  ],
  templateUrl: './to-retry.component.html',
  styleUrl: './to-retry.component.scss'
})
export class ToRetryComponent {

  constructor(protected compensationClient: CompensationClient) {
  }

  protected readonly FindCategory = FindCategory;
}
