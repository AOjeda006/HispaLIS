import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { interceptorDeTestigo } from './seguridad/interceptor-testigo';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    // El testigo se pone en un interceptor y no en `ClienteFhir`: firmar la petición y hablar FHIR
    // son dos preocupaciones distintas. El interceptor solo toca las llamadas al laboratorio.
    provideHttpClient(withFetch(), withInterceptors([interceptorDeTestigo])),
  ],
};
