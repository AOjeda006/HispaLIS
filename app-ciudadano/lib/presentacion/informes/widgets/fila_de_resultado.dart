import 'package:flutter/material.dart';

import '../../../dominio/resultado.dart';
import '../../formato.dart';

/// Una determinación en pantalla: **qué prueba, cuánto, con qué se compara y quién responde**.
///
/// Las cuatro cosas van juntas y ninguna es opcional. Un número suelto no dice nada —«4,2» es normal
/// para un potasio y alto para una creatinina— y una cifra sin saber si la ha revisado alguien
/// invita a tomar una decisión sobre un dato que todavía puede cambiar.
class FilaDeResultado extends StatelessWidget {
  /// Construye la fila.
  const FilaDeResultado({required this.resultado, super.key});

  /// El resultado que se enseña.
  final Resultado resultado;

  @override
  Widget build(BuildContext context) {
    final tipografia = Theme.of(context).textTheme;
    final rango = Formato.rango(resultado.rango);
    final valor = Formato.valor(resultado.valor);

    return Semantics(
      // El lector de pantalla lee la frase entera y no cuatro trozos sueltos.
      label:
          '${resultado.prueba}: $valor. Valores de referencia: $rango. ${_estado.descripcion}.'
          '${resultado.porQueExiste == null ? '' : ' ${resultado.porQueExiste}'}',
      excludeSemantics: true,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(child: Text(resultado.prueba, style: tipografia.titleMedium)),
                const SizedBox(width: 12),
                Text(valor, style: tipografia.titleMedium?.copyWith(fontWeight: FontWeight.bold)),
              ],
            ),
            // R5: `triggeredBy`, con PALABRAS. Un icono habría que aprendérselo, no lo lee un
            // lector de pantalla y no distingue una repetición por muestra hemolizada de una
            // re-ejecución por control de calidad fuera. Va justo debajo del nombre porque es parte
            // de qué es esta determinación, no una nota al margen.
            if (resultado.porQueExiste != null) ...[
              const SizedBox(height: 4),
              Text(
                resultado.porQueExiste!,
                style: tipografia.bodySmall?.copyWith(fontStyle: FontStyle.italic),
              ),
            ],
            const SizedBox(height: 4),
            Text('Valores de referencia: $rango', style: tipografia.bodySmall),
            const SizedBox(height: 8),
            // `Wrap` y no `Row`: «Corregido y validado por el facultativo» más la fecha no caben
            // en la anchura de un móvil, y el texto se corta antes que doblar. Que baje de línea.
            Wrap(
              spacing: 8,
              runSpacing: 4,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                _MarcaDeEstado(estado: _estado),
                if (resultado.medidoEn != null)
                  Text(
                    'Medido el ${Formato.fechaYHora(resultado.medidoEn!)}',
                    style: tipografia.bodySmall,
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  _EstadoEnPantalla get _estado => switch (resultado.estado) {
    EstadoDelResultado.validado => const _EstadoEnPantalla(
      'Validado por el facultativo',
      Icons.verified_outlined,
    ),
    EstadoDelResultado.corregido => const _EstadoEnPantalla(
      'Corregido y validado por el facultativo',
      Icons.edit_note_outlined,
    ),
    EstadoDelResultado.preliminar => const _EstadoEnPantalla(
      'Pendiente de validar por el facultativo',
      Icons.schedule_outlined,
      provisional: true,
    ),
    EstadoDelResultado.anulado => const _EstadoEnPantalla(
      'Anulado por el laboratorio',
      Icons.block_outlined,
      provisional: true,
    ),
    EstadoDelResultado.desconocido => const _EstadoEnPantalla(
      'Sin confirmar por el laboratorio',
      Icons.help_outline,
      provisional: true,
    ),
  };
}

@immutable
class _EstadoEnPantalla {
  const _EstadoEnPantalla(this.descripcion, this.icono, {this.provisional = false});

  final String descripcion;
  final IconData icono;
  final bool provisional;
}

class _MarcaDeEstado extends StatelessWidget {
  const _MarcaDeEstado({required this.estado});

  final _EstadoEnPantalla estado;

  @override
  Widget build(BuildContext context) {
    final colores = Theme.of(context).colorScheme;
    // El color acompaña, no informa: quien no lo distingue tiene el icono y el texto.
    final color = estado.provisional ? colores.tertiary : colores.primary;

    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(estado.icono, size: 16, color: color),
        const SizedBox(width: 4),
        Flexible(
          child: Text(
            estado.descripcion,
            style: Theme.of(context).textTheme.bodySmall?.copyWith(color: color),
          ),
        ),
      ],
    );
  }
}
