import {Component} from '@angular/core';
import {FindCategory} from "../api/CompensationClient";
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

  protected readonly FindCategory = FindCategory;
}
