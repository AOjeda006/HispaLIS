import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { BASE_TERMINOLOGIA, VS_PRUEBAS_DEL_CATALOGO } from './configuracion';

/**
 * El catálogo de pruebas que oferta el laboratorio, **preguntado al servidor de terminología**.
 *
 * **Es el invariante D15 en la web.** El `ValueSet` que se expande aquí es el mismo que publica la
 * IG, el mismo contra el que valida el backend y el mismo que consume el generador de datos
 * sintéticos. Una lista de códigos escrita en TypeScript sería una cuarta versión de la verdad, y la
 * primera en quedarse vieja: el día que se añada una prueba al catálogo, la web seguiría ofreciendo
 * las de antes sin que fallase nada.
 *
 * Hasta hace poco la lista se **congelaba en el build**: un guion copiaba el `CodeSystem` de
 * `ig/fsh-generated/` dentro del paquete que se descarga el navegador. Era el mismo fichero, sí,
 * pero el de la versión con la que se compiló. Añadir una prueba al catálogo exigía reconstruir y
 * volver a desplegar la web, y hasta que alguien lo hiciera la web ofrecía un catálogo viejo **sin
 * que nada fallara**. Ahora se pregunta con `$expand`, que es la operación estándar para justamente
 * esto, y la web se entera en la siguiente sesión.
 *
 * Hasta el `system` sale de la respuesta. Repetirlo aquí como constante sería volver a escribir a
 * mano lo que ya está escrito, que es de donde salen las divergencias.
 */

/** Una prueba tal y como se ofrece al pedirla. */
export interface PruebaDelCatalogo {
  readonly codigo: string;
  /** Su nombre en español; viene así de la guía y no se traduce en el cliente. */
  readonly display: string;
}

export interface Catalogo {
  /** El `system` del catálogo, tal y como lo devuelve la expansión. */
  readonly system: string;
  readonly pruebas: readonly PruebaDelCatalogo[];
}

/** La parte de la respuesta de `$expand` que aquí se usa. */
interface ExpansionDelValueSet {
  readonly expansion?: {
    readonly contains?: readonly {
      readonly system?: string;
      readonly code?: string;
      readonly display?: string;
    }[];
  };
}

@Injectable({ providedIn: 'root' })
export class CatalogoDePruebas {
  private readonly http = inject(HttpClient);
  private readonly terminologia = inject(BASE_TERMINOLOGIA);
  private readonly valueSet = inject(VS_PRUEBAS_DEL_CATALOGO);

  /** El catálogo se pide una vez por sesión: es terminología, no cambia mientras se trabaja. */
  private pedido?: Promise<Catalogo>;

  cargar(): Promise<Catalogo> {
    // `count` explícito: la expansión de un `ValueSet` viene paginada, y el valor por omisión lo
    // pone el servidor. Sin él, un catálogo que crezca por encima de esa página se cortaría en
    // silencio y la web dejaría de ofrecer las últimas pruebas sin que fallase nada.
    const parametros = new HttpParams().set('url', this.valueSet).set('count', 500);

    this.pedido ??= firstValueFrom(
      this.http.get<ExpansionDelValueSet>(`${this.terminologia}/ValueSet/$expand`, {
        params: parametros,
      }),
    ).then(comoCatalogo);
    return this.pedido;
  }
}

function comoCatalogo(respuesta: ExpansionDelValueSet): Catalogo {
  const contenidos = (respuesta.expansion?.contains ?? []).filter(
    (concepto) => concepto.code !== undefined,
  );

  return {
    system: contenidos[0]?.system ?? '',
    pruebas: contenidos.map((concepto) => ({
      codigo: concepto.code as string,
      display: concepto.display ?? (concepto.code as string),
    })),
  };
}
