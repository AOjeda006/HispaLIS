import { computed, inject, Injectable, signal } from '@angular/core';

import { Catalogo, CatalogoDePruebas } from '../fhir/catalogo';
import { ClienteFhir, ErrorDelLaboratorio, Pagina } from '../fhir/cliente-fhir';
import { SIN_RESPUESTA } from '../fhir/operation-outcome';
import { porIdentificador, SID_NHC } from '../fhir/sistemas';
import { DiagnosticReport, Observation, Patient } from '../fhir/tipos';

/**
 * La lógica de la pantalla de consulta de informe. El componente solo la orquesta.
 *
 * Se identifica al paciente por su número de historia, se listan sus informes —del más reciente al
 * más antiguo— y se abre uno para ver sus resultados.
 *
 * **Los informes llegan paginados y se sigue el enlace del servidor.** No es un lujo para un
 * paciente con tres analíticas: es que un paciente crónico acumula decenas, y una lista que se corta
 * en la primera página sin decirlo esconde justamente los informes antiguos que se van a consultar.
 */

/** Cuántos informes se piden por página. Suficiente para ver el año en curso sin desbordar. */
const POR_PAGINA = 10;

@Injectable()
export class ConsultaInformeVm {
  private readonly cliente = inject(ClienteFhir);
  private readonly pruebasDelLaboratorio = inject(CatalogoDePruebas);

  private readonly pagina = signal<Pagina<DiagnosticReport> | undefined>(undefined);
  private readonly catalogo = signal<Catalogo | undefined>(undefined);

  readonly paciente = signal<Patient | undefined>(undefined);
  readonly informes = signal<readonly DiagnosticReport[]>([]);
  readonly abierto = signal<DiagnosticReport | undefined>(undefined);
  readonly resultados = signal<readonly Observation[]>([]);
  readonly trabajando = signal(false);
  readonly error = signal('');
  readonly aviso = signal('');

  /** Cuántos informes tiene en total, según el servidor. */
  readonly total = computed(() => this.pagina()?.total);

  /** Quedan informes por traer: lo dice el servidor con su enlace, no una cuenta del cliente. */
  readonly hayMas = computed(() => !!this.pagina()?.siguiente);

  /** El sexo del paciente, que es lo que decide qué rango de referencia aplica. */
  readonly sexo = computed(() => this.paciente()?.gender);

  /**
   * Trae el catálogo, que es lo que pone nombre a cada resultado.
   *
   * La proyección publica el código del catálogo sin `display` a propósito: el término lo resuelve
   * quien tiene la terminología, y fijarlo en el recurso lo congela. Aquí se resuelve con el mismo
   * `CodeSystem` que publica la guía — no se traduce ni se inventa nada en el cliente.
   */
  async cargarCatalogo(): Promise<void> {
    await this.intentar(async () => this.catalogo.set(await this.pruebasDelLaboratorio.cargar()));
  }

  /** El nombre de la prueba en español; si el catálogo no la conoce, su propio código. */
  nombreDePrueba(resultado: Observation): string {
    const codificacion = (resultado.code.coding ?? []).find(
      (codigo) => codigo.system === this.catalogo()?.system,
    );
    const codigo = codificacion?.code;
    const conocida = (this.catalogo()?.pruebas ?? []).find((prueba) => prueba.codigo === codigo);

    return conocida?.display ?? resultado.code.text ?? codificacion?.display ?? codigo ?? '';
  }

  async buscar(nhc: string): Promise<void> {
    await this.intentar(async () => {
      this.olvidarLoAnterior();

      const pacientes = await this.cliente.buscar<Patient>('Patient', {
        identifier: porIdentificador(SID_NHC, nhc.trim()),
      });
      const paciente = pacientes.recursos[0];
      this.paciente.set(paciente);

      if (!paciente?.id) {
        this.aviso.set(`No consta ningún paciente con el número de historia ${nhc}.`);
        return;
      }

      const primera = await this.cliente.buscar<DiagnosticReport>('DiagnosticReport', {
        patient: `Patient/${paciente.id}`,
        _sort: '-issued',
        _count: POR_PAGINA,
      });
      this.pagina.set(primera);
      this.informes.set(primera.recursos);

      if (primera.recursos.length === 0) {
        this.aviso.set('Este paciente todavía no tiene ningún informe emitido.');
      }
    });
  }

  /** Trae la página siguiente **por el enlace que dio el servidor**, y la añade a la lista. */
  async verMas(): Promise<void> {
    const actual = this.pagina();
    if (!actual?.siguiente) {
      return;
    }
    await this.intentar(async () => {
      const siguiente = await this.cliente.siguiente(actual);
      this.pagina.set(siguiente);
      this.informes.update((vistos) => [...vistos, ...siguiente.recursos]);
    });
  }

  /**
   * Abre un informe y trae sus resultados.
   *
   * El informe solo lleva referencias a sus `Observation`; el valor, la unidad y el rango viven en
   * ellas. Se leen todas a la vez porque son lecturas independientes y son las que dan sentido a la
   * pantalla: un informe sin sus resultados es una fecha y poco más.
   */
  async abrir(informe: DiagnosticReport): Promise<void> {
    await this.intentar(async () => {
      this.abierto.set(informe);
      this.resultados.set([]);

      const referencias = (informe.result ?? [])
        .map((resultado) => resultado.reference)
        .filter((referencia): referencia is string => !!referencia);

      this.resultados.set(
        await Promise.all(
          referencias.map((referencia) => this.cliente.leer<Observation>(referencia)),
        ),
      );
    });
  }

  cerrar(): void {
    this.abierto.set(undefined);
    this.resultados.set([]);
  }

  private olvidarLoAnterior(): void {
    this.paciente.set(undefined);
    this.informes.set([]);
    this.pagina.set(undefined);
    this.abierto.set(undefined);
    this.resultados.set([]);
  }

  private async intentar(paso: () => Promise<void>): Promise<void> {
    this.trabajando.set(true);
    this.error.set('');
    this.aviso.set('');
    try {
      await paso();
    } catch (fallo) {
      this.error.set(fallo instanceof ErrorDelLaboratorio ? fallo.message : SIN_RESPUESTA);
    } finally {
      this.trabajando.set(false);
    }
  }
}
