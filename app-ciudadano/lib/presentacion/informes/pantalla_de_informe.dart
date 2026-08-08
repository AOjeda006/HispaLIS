import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../formato.dart';
import 'informes_vm.dart';
import 'widgets/aviso_de_validacion.dart';
import 'widgets/fila_de_resultado.dart';

/// El detalle de una analítica: el aviso arriba y las determinaciones debajo.
///
/// El orden de la pantalla es deliberado. Primero si esto lo ha revisado alguien, después las
/// cifras. Al revés, la persona ya ha leído el número antes de enterarse de que puede cambiar.
class PantallaDeInforme extends StatelessWidget {
  /// Construye la pantalla del informe con ese id.
  const PantallaDeInforme({required this.informeId, super.key});

  /// El id del informe que se está mirando.
  final String informeId;

  @override
  Widget build(BuildContext context) {
    final informe = context.watch<InformesVm>().porId(informeId);

    if (informe == null) {
      return Scaffold(
        appBar: AppBar(title: const Text('Analítica')),
        body: const Center(
          child: Padding(
            padding: EdgeInsets.all(24),
            child: Text(
              'Esta analítica ya no está en la lista. Vuelve atrás y actualiza tus resultados.',
              textAlign: TextAlign.center,
            ),
          ),
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(title: Text(informe.titulo)),
      body: ListView(
        padding: const EdgeInsets.only(bottom: 24),
        children: [
          AvisoDeValidacion(informe: informe),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
            child: Text(
              'Fecha: ${Formato.fechaYHora(informe.fecha)}',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
          const Divider(height: 24),
          for (final resultado in informe.resultados) ...[
            FilaDeResultado(resultado: resultado),
            const Divider(height: 1),
          ],
        ],
      ),
    );
  }
}
