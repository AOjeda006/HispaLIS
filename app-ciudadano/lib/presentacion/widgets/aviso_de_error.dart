import 'package:flutter/material.dart';

import '../../nucleo/errores/error_de_la_app.dart';

/// Cómo se le cuenta a una persona que algo ha salido mal.
///
/// Un solo widget para todos los fallos, y siempre con el [ErrorDeLaApp.mensaje] tal cual: los
/// códigos, los cuerpos de respuesta y las trazas se quedan en el cliente FHIR. Lo que llega aquí ya
/// está escrito en español y sin jerga.
class AvisoDeError extends StatelessWidget {
  /// Construye el aviso. [reintentar] es opcional: hay fallos que no se arreglan repitiendo.
  const AvisoDeError({required this.error, this.reintentar, super.key});

  /// El fallo que se cuenta.
  final ErrorDeLaApp error;

  /// Qué hacer si la persona quiere volver a intentarlo.
  final VoidCallback? reintentar;

  @override
  Widget build(BuildContext context) {
    final colores = Theme.of(context).colorScheme;

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(_icono, size: 48, color: colores.error),
            const SizedBox(height: 16),
            Text(
              error.mensaje,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.bodyLarge,
            ),
            if (reintentar != null) ...[
              const SizedBox(height: 24),
              FilledButton.tonal(onPressed: reintentar, child: const Text('Reintentar')),
            ],
          ],
        ),
      ),
    );
  }

  IconData get _icono => switch (error.codigo) {
    CodigoDeError.sinRed => Icons.wifi_off_outlined,
    CodigoDeError.sesionCaducada => Icons.lock_clock_outlined,
    CodigoDeError.sinPermiso => Icons.no_accounts_outlined,
    CodigoDeError.autorizacionRechazada => Icons.person_off_outlined,
    CodigoDeError.laboratorio => Icons.error_outline,
  };
}
