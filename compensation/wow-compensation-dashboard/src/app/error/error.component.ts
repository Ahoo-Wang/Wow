import {Component, Input} from '@angular/core';

import {NzCodeEditorModule} from 'ng-zorro-antd/code-editor';

import {ExecutionFailedState} from "../api/ExecutionFailedState";
import {NzDescriptionsComponent, NzDescriptionsItemComponent} from "ng-zorro-antd/descriptions";
import {FormsModule} from "@angular/forms";
import {NzTypographyComponent} from "ng-zorro-antd/typography";

@Component({
    selector: 'app-error',
    imports: [NzCodeEditorModule, NzDescriptionsComponent, NzDescriptionsItemComponent, FormsModule, NzTypographyComponent],
    templateUrl: './error.component.html',
    styleUrl: './error.component.scss'
})
export class ErrorComponent {
  @Input({required: true}) state!: ExecutionFailedState
}
