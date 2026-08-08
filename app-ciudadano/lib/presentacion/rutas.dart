import 'package:go_router/go_router.dart';

import '../nucleo/seguridad/sesion.dart';
import 'entrada/pantalla_de_entrada.dart';
import 'informes/pantalla_de_informe.dart';
import 'informes/pantalla_de_informes.dart';

/// Las direcciones de la app y quién puede llegar a cada una.
///
/// La guarda de sesión vive **aquí y en un solo sitio**. Repartirla por las pantallas es como se
/// consigue que alguna se quede sin ella: basta con añadir una nueva y olvidarse. Aun así, esto no
/// es control de acceso —la app no protege nada, protege el laboratorio—; es no enseñar una pantalla
/// vacía a quien todavía no se ha identificado.
abstract final class Rutas {
  /// La pantalla de entrada.
  static const String entrada = '/';

  /// La lista de analíticas.
  static const String informes = '/informes';

  /// El detalle de una analítica concreta.
  static String informe(String id) => '$informes/$id';

  /// Monta el enrutador. [sesion] es lo que lo hace reaccionar al entrar y al salir.
  static GoRouter enrutador(Sesion sesion) => GoRouter(
    initialLocation: entrada,
    refreshListenable: sesion,
    redirect: (context, estado) {
      final dentro = sesion.datos != null;
      final enLaEntrada = estado.matchedLocation == entrada;
      if (!dentro) {
        return enLaEntrada ? null : entrada;
      }
      return enLaEntrada ? informes : null;
    },
    routes: [
      GoRoute(path: entrada, builder: (context, estado) => const PantallaDeEntrada()),
      GoRoute(
        path: informes,
        builder: (context, estado) => const PantallaDeInformes(),
        routes: [
          GoRoute(
            path: ':id',
            builder: (context, estado) =>
                PantallaDeInforme(informeId: estado.pathParameters['id']!),
          ),
        ],
      ),
    ],
  );
}
