import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../widgets/aviso_de_error.dart';
import 'entrada_vm.dart';

/// La primera pantalla: explicar qué es esto y llevar a identificarse.
///
/// No hay campos de usuario ni de contraseña, y eso es la garantía, no una carencia: **las
/// credenciales no pasan por esta app**. Se teclean en la página del servidor de identidad, dentro
/// del navegador del sistema, que enseña la barra de direcciones y el candado. Una app que pinta su
/// propio formulario de acceso es indistinguible de una que se los queda.
class PantallaDeEntrada extends StatelessWidget {
  /// Construye la pantalla.
  const PantallaDeEntrada({super.key});

  @override
  Widget build(BuildContext context) {
    final vm = context.watch<EntradaVm>();
    final tipografia = Theme.of(context).textTheme;

    return Scaffold(
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    Icons.biotech_outlined,
                    size: 72,
                    color: Theme.of(context).colorScheme.primary,
                  ),
                  const SizedBox(height: 24),
                  Text('HispaLIS', style: tipografia.headlineMedium, textAlign: TextAlign.center),
                  const SizedBox(height: 8),
                  Text(
                    'Consulta los resultados de tus análisis',
                    style: tipografia.bodyLarge,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 32),
                  if (vm.error != null) ...[
                    AvisoDeError(error: vm.error!),
                    const SizedBox(height: 16),
                  ],
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: vm.trabajando ? null : () => unawaitedEntrar(vm),
                      icon: vm.trabajando
                          ? const SizedBox.square(
                              dimension: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.login),
                      label: Text(vm.trabajando ? 'Identificándote…' : 'Identificarme'),
                    ),
                  ),
                  const SizedBox(height: 16),
                  Text(
                    'Te llevaremos al servidor de identidad del laboratorio. HispaLIS no ve tu '
                    'contraseña en ningún momento.',
                    style: tipografia.bodySmall,
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Arranca la identificación sin esperar al resultado: el modelo ya avisa de lo que pase.
  ///
  /// Está aparte y con nombre porque `unawaited_futures` prohíbe soltar un futuro en medio de un
  /// `onPressed`, y esconderlo con un `// ignore` sería tapar la regla en vez de cumplirla.
  static void unawaitedEntrar(EntradaVm vm) {
    vm.entrar().ignore();
  }
}
