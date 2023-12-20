import {Component} from '@angular/core';
import {CompensationClient, FindCategory} from "../api/CompensationClient";
import {FailedListComponent} from "../failed-list/failed-list.component";

@Component({
  selector: 'app-succeeded',
  standalone: true,
  imports: [
    FailedListComponent
  ],
  templateUrl: './succeeded.component.html',
  styleUrl: './succeeded.component.scss'
})
export class SucceededComponent {
  constructor(protected compensationClient: CompensationClient) {
  }

  protected readonly FindCategory = FindCategory;
}
