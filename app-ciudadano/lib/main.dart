import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:go_router/go_router.dart';
import 'package:provider/provider.dart';

import 'nucleo/di/contenedor.dart';
import 'nucleo/seguridad/autenticacion.dart';
import 'nucleo/seguridad/sesion.dart';
import 'presentacion/entrada/entrada_vm.dart';
import 'presentacion/informes/informes_vm.dart';
import 'presentacion/tema.dart';

/// Arranca la app del ciudadano.
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Contenedor.montar();
  runApp(const AppDelCiudadano());
}

/// La raíz de la app: dependencias, idioma, tema y enrutador.
class AppDelCiudadano extends StatelessWidget {
  /// Construye la raíz.
  const AppDelCiudadano({super.key});

  @override
  Widget build(BuildContext context) => MultiProvider(
    providers: [
      ChangeNotifierProvider<Sesion>.value(value: Contenedor.dame<Sesion>()),
      ChangeNotifierProvider<EntradaVm>.value(value: Contenedor.dame<EntradaVm>()),
      ChangeNotifierProvider<InformesVm>.value(value: Contenedor.dame<InformesVm>()),
      Provider<Autenticacion>.value(value: Contenedor.dame<Autenticacion>()),
    ],
    child: MaterialApp.router(
      title: 'HispaLIS — Mis resultados',
      theme: Tema.claro,
      darkTheme: Tema.oscuro,
      routerConfig: Contenedor.dame<GoRouter>(),
      // Un solo idioma, y es el del laboratorio. Los textos del propio Flutter —«Cancelar»,
      // «Actualizar»— también, que si no salen en inglés en medio de una pantalla en español.
      locale: const Locale('es', 'ES'),
      supportedLocales: const [Locale('es', 'ES')],
      localizationsDelegates: const [
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
    ),
  );
}
