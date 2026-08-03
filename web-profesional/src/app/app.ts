import { Component, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/**
 * Componente raíz de la web del profesional del laboratorio.
 *
 * Las pantallas de alta de petición y consulta de informe llegan con el ítem 14 del plan; aquí solo
 * vive el armazón de la aplicación.
 */
@Component({
  selector: 'lab-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected readonly titulo = signal('HispaLIS · Laboratorio');
}
