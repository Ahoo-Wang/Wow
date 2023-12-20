import {Component, Input} from '@angular/core';

import {NzCodeEditorModule} from 'ng-zorro-antd/code-editor';

import {ExecutionFailedState} from "../api/ExecutionFailedState";
import {NzDescriptionsComponent, NzDescriptionsItemComponent} from "ng-zorro-antd/descriptions";
import {NzBadgeComponent} from "ng-zorro-antd/badge";
import {FormsModule} from "@angular/forms";
import {NgStyle} from "@angular/common";

@Component({
  selector: 'app-error',
  standalone: true,
  imports: [NzCodeEditorModule, NzDescriptionsComponent, NzDescriptionsItemComponent, NzBadgeComponent, FormsModule, NgStyle],
  templateUrl: './error.component.html',
  styleUrl: './error.component.scss'
})
export class ErrorComponent {
  @Input({ required: true })  state!: ExecutionFailedState
}
