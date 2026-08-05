import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';

import { identificador, nombreCompleto } from '../fhir/presentacion';
import { SID_COLEGIADO, SID_NHC } from '../fhir/sistemas';
import { Patient, Practitioner } from '../fhir/tipos';
import { AltaPeticionVm } from './alta-peticion.vm';

/**
 * Pantalla de alta de petición: el mostrador del laboratorio.
 *
 * El componente no habla con la API: eso es del {@link AltaPeticionVm}. Lo que sí hace es el gesto
 * que hace útil la pantalla — cuando el número de historia no consta, abre el formulario de alta con
 * ese número ya escrito, en vez de obligar a teclearlo dos veces.
 */
@Component({
  selector: 'lab-alta-peticion',
  imports: [ReactiveFormsModule],
  templateUrl: './alta-peticion.html',
  styleUrl: './alta-peticion.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AltaPeticionVm],
})
export class AltaPeticion implements OnInit {
  private readonly formularios = inject(FormBuilder);

  protected readonly vm = inject(AltaPeticionVm);
  protected readonly altaDePaciente = signal(false);
  protected readonly altaDeFacultativo = signal(false);

  /** Ocho dígitos: es el formato del NHC que emite el laboratorio (invariante `hlis-nhc-1`). */
  protected readonly busqueda = this.formularios.nonNullable.group({
    nhc: ['', [Validators.required, Validators.pattern(/^\d{8}$/)]],
  });

  protected readonly filiacion = this.formularios.nonNullable.group({
    nhc: ['', [Validators.required, Validators.pattern(/^\d{8}$/)]],
    apellidos: ['', Validators.required],
    nombre: ['', Validators.required],
    sexo: this.formularios.nonNullable.control<'male' | 'female' | 'other' | 'unknown'>('unknown'),
    fechaDeNacimiento: [''],
    dniNie: [''],
  });

  protected readonly busquedaDeFacultativo = this.formularios.nonNullable.group({
    colegiado: ['', Validators.required],
  });

  protected readonly nuevoFacultativo = this.formularios.nonNullable.group({
    colegiado: ['', Validators.required],
    apellidos: ['', Validators.required],
    nombre: ['', Validators.required],
  });

  ngOnInit(): void {
    void this.vm.cargarCatalogo();
  }

  protected nombreDe = nombreCompleto;

  protected nhcDe(paciente: Patient): string {
    return identificador(paciente, SID_NHC);
  }

  protected colegiadoDe(facultativo: Practitioner): string {
    return identificador(facultativo, SID_COLEGIADO);
  }

  protected async buscarPaciente(): Promise<void> {
    const { nhc } = this.busqueda.getRawValue();
    await this.vm.buscarPaciente(nhc);

    if (!this.vm.paciente() && !this.vm.error()) {
      this.filiacion.patchValue({ nhc });
      this.altaDePaciente.set(true);
    }
  }

  protected async registrarPaciente(): Promise<void> {
    await this.vm.registrarPaciente(this.filiacion.getRawValue());
    if (this.vm.paciente()) {
      this.altaDePaciente.set(false);
    }
  }

  protected async buscarFacultativo(): Promise<void> {
    const { colegiado } = this.busquedaDeFacultativo.getRawValue();
    await this.vm.buscarFacultativo(colegiado);

    if (!this.vm.facultativo() && !this.vm.error()) {
      this.nuevoFacultativo.patchValue({ colegiado });
      this.altaDeFacultativo.set(true);
    }
  }

  protected async registrarFacultativo(): Promise<void> {
    await this.vm.registrarFacultativo(this.nuevoFacultativo.getRawValue());
    if (this.vm.facultativo()) {
      this.altaDeFacultativo.set(false);
    }
  }

  protected empezarDeNuevo(): void {
    this.vm.empezarDeNuevo();
    this.altaDePaciente.set(false);
    this.altaDeFacultativo.set(false);
    this.busqueda.reset();
    this.filiacion.reset();
    this.busquedaDeFacultativo.reset();
    this.nuevoFacultativo.reset();
  }
}
