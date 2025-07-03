import {ApplicationConfig, importProvidersFrom} from '@angular/core';
import {provideRouter} from '@angular/router';

import {routes} from './app.routes';
import {zh_CN, provideNzI18n} from 'ng-zorro-antd/i18n';
import {registerLocaleData} from '@angular/common';
import zh from '@angular/common/locales/zh';
import {FormsModule} from '@angular/forms';
import {provideHttpClient, withInterceptorsFromDi} from '@angular/common/http';
import {provideAnimations} from '@angular/platform-browser/animations';

registerLocaleData(zh);

export const appConfig: ApplicationConfig = {
  providers: [provideRouter(routes),

    provideNzI18n(zh_CN),
    importProvidersFrom(FormsModule),
    provideHttpClient(withInterceptorsFromDi()),
    provideAnimations()]
};
