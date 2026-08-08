import 'package:flutter/material.dart';

import '../../../dominio/informe.dart';

/// El aviso que encabeza una analítica que **todavía no ha firmado un facultativo**.
///
/// Se dice con todas las letras y arriba del todo, no en una nota al pie. La app enseña resultados
/// que el analizador ya ha medido porque ocultarlos sería peor —a la persona le faltarían datos que
/// existen—, pero un valor sin revisar puede cambiar, y quien lo lee tiene que saberlo antes de
/// leerlo, no después.
class AvisoDeValidacion extends StatelessWidget {
  /// Construye el aviso para [informe]. Se pinta solo si hace falta.
  const AvisoDeValidacion({required this.informe, super.key});

  /// Texto exacto del aviso. Es el que comprueban los tests.
  static const String textoPendiente =
      'Esta analítica todavía NO está validada por el facultativo. Los valores pueden cambiar: '
      'no tomes decisiones sobre tu salud con ellos.';

  /// La analítica a la que acompaña.
  final Informe informe;

  @override
  Widget build(BuildContext context) {
    if (informe.validadoPorFacultativo) {
      return const SizedBox.shrink();
    }

    final colores = Theme.of(context).colorScheme;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(16, 8, 16, 0),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: colores.tertiaryContainer,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(Icons.warning_amber_outlined, color: colores.onTertiaryContainer),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              textoPendiente,
              style: Theme.of(
                context,
              ).textTheme.bodyMedium?.copyWith(color: colores.onTertiaryContainer),
            ),
          ),
        ],
      ),
    );
  }
}
