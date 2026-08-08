import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';

import { ErrorDeLanzamiento, LanzamientoSmart } from './lanzamiento-smart';

/**
 * `/launch`: la puerta por la que el sistema clínico abre esta web (EHR launch).
 *
 * No tiene interfaz que merezca ese nombre y no debe tenerla: lo único que hace es descubrir,
 * preparar el PKCE y salir hacia el servidor de autorización. Lo que se enseña es un aviso de que se
 * está redirigiendo, y el motivo si algo impide seguir.
 *
 * Sirve también para el **lanzamiento autónomo**: entrando en `/launch` sin parámetros, la
 * aplicación se lanza contra el laboratorio por defecto y sin contexto de paciente.
 */
@Component({
  selector: 'lab-lanzamiento',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="lanzamiento">
      @if (fallo()) {
        <h1>No se ha podido entrar</h1>
        <p role="alert">{{ fallo() }}</p>
        <p>
          Vuelve a abrir HispaLIS desde el sistema clínico. Si sigue pasando, avisa a informática.
        </p>
      } @else {
        <h1>Entrando en HispaLIS…</h1>
        <p>Te estamos llevando a identificarte.</p>
      }
    </section>
  `,
  styles: `
    .lanzamiento {
      margin: 4rem auto;
      max-width: 32rem;
      text-align: center;
    }
  `,
})
export class Lanzamiento implements OnInit {
  private readonly ruta = inject(ActivatedRoute);
  private readonly smart = inject(LanzamientoSmart);

  protected readonly fallo = signal<string | undefined>(undefined);

  ngOnInit(): void {
    void this.lanzar();
  }

  private async lanzar(): Promise<void> {
    const parametros = this.ruta.snapshot.queryParamMap;
    try {
      const destino = await this.smart.comenzar(
        parametros.get('iss'),
        parametros.get('launch'),
        parametros.get('destino') ?? '/peticiones/nueva',
      );
      // `replace` y no `assign`: así el botón de atrás del navegador no devuelve a esta página, que
      // volvería a lanzar y generaría un `state` nuevo dejando el anterior colgado.
      globalThis.location.replace(destino);
    } catch (error) {
      this.fallo.set(
        error instanceof ErrorDeLanzamiento
          ? error.message
          : 'No se ha podido iniciar la identificación.',
      );
    }
  }
}
