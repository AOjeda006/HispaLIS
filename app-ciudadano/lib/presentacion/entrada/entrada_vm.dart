import 'package:flutter/foundation.dart';

import '../../nucleo/errores/error_de_la_app.dart';
import '../../nucleo/seguridad/autenticacion.dart';

/// La lógica de la pantalla de entrada: un botón, y qué contar cuando no sale bien.
final class EntradaVm extends ChangeNotifier {
  /// Construye el modelo sobre el servicio de identidad.
  EntradaVm(this._autenticacion);

  final Autenticacion _autenticacion;

  bool _trabajando = false;
  ErrorDeLaApp? _error;

  /// Si el flujo de identificación está en marcha.
  bool get trabajando => _trabajando;

  /// El último fallo, o `null` si no lo hubo.
  ErrorDeLaApp? get error => _error;

  /// Lleva a la persona al servidor de identidad y abre la sesión al volver.
  Future<void> entrar() async {
    _trabajando = true;
    _error = null;
    notifyListeners();
    try {
      await _autenticacion.entrar();
    } on ErrorDeLaApp catch (fallo) {
      _error = fallo;
    } finally {
      _trabajando = false;
      notifyListeners();
    }
  }
}
