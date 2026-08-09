import { ChangeDetectionStrategy, Component, inject, OnInit } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import {
  fechaLegible,
  identificador,
  nombreCompleto,
  porQueExiste,
  rangoDeReferencia,
  valorConUnidad,
} from '../fhir/presentacion';
import { SID_NHC } from '../fhir/sistemas';
import { Observation, Patient } from '../fhir/tipos';
import { ConsultaInformeVm } from './consulta-informe.vm';

/**
 * Pantalla de consulta de informe.
 *
 * Lo que hace clínicamente útil esta pantalla no es la lista de informes: es que cada resultado se
 * enseñe **con su unidad y con el rango que le corresponde a este paciente**. Un «13» a secas no se
 * puede interpretar, y un 13 g/dL de hemoglobina es normal en una mujer y bajo en un hombre.
 */
@Component({
  selector: 'lab-consulta-informe',
  imports: [ReactiveFormsModule],
  templateUrl: './consulta-informe.html',
  styleUrl: './consulta-informe.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [ConsultaInformeVm],
})
export class ConsultaInforme implements OnInit {
  private readonly formularios = inject(FormBuilder);

  protected readonly vm = inject(ConsultaInformeVm);
  protected readonly nombreDe = nombreCompleto;
  protected readonly fechaLegible = fechaLegible;
  protected readonly valorConUnidad = valorConUnidad;

  /** Por qué existe una determinación que nadie pidió. Vacío en casi todas. */
  protected readonly porQueExiste = porQueExiste;

  protected readonly busqueda = this.formularios.nonNullable.group({
    nhc: ['', [Validators.required, Validators.pattern(/^\d{8}$/)]],
  });

  ngOnInit(): void {
    void this.vm.cargarCatalogo();
  }

  protected nhcDe(paciente: Patient): string {
    return identificador(paciente, SID_NHC);
  }

  /** El rango que aplica al **sexo de este paciente**; la proyección publica todos y no elige. */
  protected rangoDe(resultado: Observation): string {
    return rangoDeReferencia(resultado, this.vm.sexo());
  }

  protected buscar(): void {
    void this.vm.buscar(this.busqueda.getRawValue().nhc);
  }
}
