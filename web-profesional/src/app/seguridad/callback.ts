import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { ErrorDeLanzamiento, LanzamientoSmart } from './lanzamiento-smart';

/**
 * `/callback`: la vuelta del servidor de autorización con el código.
 *
 * Aquí se canjea el código por el testigo y se abre la sesión. La navegación al destino se hace con
 * el enrutador y con `replaceUrl`, para que la URL con el `code` dentro **no se quede en el
 * historial**: es de un solo uso, pero lo que no se guarda no se puede filtrar por encima del hombro
 * ni en una captura de pantalla.
 */
@Component({
  selector: 'lab-callback',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="callback">
      @if (fallo()) {
        <h1>No se ha podido completar la entrada</h1>
        <p role="alert">{{ fallo() }}</p>
        <p>Vuelve a abrir HispaLIS desde el sistema clínico.</p>
      } @else {
        <h1>Terminando de entrar…</h1>
      }
    </section>
  `,
  styles: `
    .callback {
      margin: 4rem auto;
      max-width: 32rem;
      text-align: center;
    }
  `,
})
export class Callback implements OnInit {
  private readonly ruta = inject(ActivatedRoute);
  private readonly enrutador = inject(Router);
  private readonly smart = inject(LanzamientoSmart);

  protected readonly fallo = signal<string | undefined>(undefined);

  ngOnInit(): void {
    void this.completar();
  }

  private async completar(): Promise<void> {
    const parametros = this.ruta.snapshot.queryParamMap;
    try {
      const destino = await this.smart.terminar(
        parametros.get('code'),
        parametros.get('state'),
        parametros.get('error'),
      );
      await this.enrutador.navigateByUrl(destino, { replaceUrl: true });
    } catch (error) {
      this.fallo.set(
        error instanceof ErrorDeLanzamiento
          ? error.message
          : 'No se ha podido canjear el código de autorización.',
      );
    }
  }
}
