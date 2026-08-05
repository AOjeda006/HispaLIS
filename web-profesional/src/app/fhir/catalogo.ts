import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { URL_CATALOGO } from './configuracion';

/**
 * El catálogo de pruebas que oferta el laboratorio, leído de la guía de implementación.
 *
 * **Es el invariante D15 en la web.** El `CodeSystem` que se lee aquí es el mismo que publica la IG,
 * el mismo que valida el backend y el mismo que consume el generador de datos sintéticos. Una lista
 * de códigos escrita en TypeScript sería una cuarta versión de la verdad, y la primera en quedarse
 * vieja: el día que se añada una prueba al catálogo, la web seguiría ofreciendo las de antes sin que
 * fallase nada.
 *
 * Hasta el `system` sale del fichero. Repetirlo aquí como constante sería volver a escribir a mano
 * lo que ya está escrito, que es de donde salen las divergencias.
 */

/** Una prueba tal y como se ofrece al pedirla. */
export interface PruebaDelCatalogo {
  readonly codigo: string;
  /** Su nombre en español; viene así de la guía y no se traduce en el cliente. */
  readonly display: string;
}

export interface Catalogo {
  /** El `system` del catálogo, tal y como lo declara la guía. */
  readonly system: string;
  readonly pruebas: readonly PruebaDelCatalogo[];
}

/** La parte del `CodeSystem` de FHIR que aquí se usa. */
interface CodeSystemDelCatalogo {
  readonly url: string;
  readonly concept?: readonly { readonly code: string; readonly display: string }[];
}

@Injectable({ providedIn: 'root' })
export class CatalogoDePruebas {
  private readonly http = inject(HttpClient);
  private readonly url = inject(URL_CATALOGO);

  /** El catálogo se pide una vez por sesión: es terminología, no cambia mientras se trabaja. */
  private pedido?: Promise<Catalogo>;

  cargar(): Promise<Catalogo> {
    this.pedido ??= firstValueFrom(this.http.get<CodeSystemDelCatalogo>(this.url)).then(
      comoCatalogo,
    );
    return this.pedido;
  }
}

function comoCatalogo(codeSystem: CodeSystemDelCatalogo): Catalogo {
  return {
    system: codeSystem.url,
    pruebas: (codeSystem.concept ?? []).map((concepto) => ({
      codigo: concepto.code,
      display: concepto.display,
    })),
  };
}
