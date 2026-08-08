import 'package:flutter/material.dart';

/// El aspecto de la app.
///
/// Material 3 con un color semilla y poco más. Dos decisiones que no son de gusto: el tamaño de
/// letra **no se recorta nunca** por debajo del que la persona haya elegido en su móvil —quien
/// consulta una analítica muchas veces no ve bien de cerca— y el color no informa por sí solo; donde
/// hay color hay también un icono y un texto.
abstract final class Tema {
  /// El color del que sale toda la paleta.
  static const Color _semilla = Color(0xFF00696D);

  /// El tema claro.
  static ThemeData get claro => _construir(Brightness.light);

  /// El tema oscuro.
  static ThemeData get oscuro => _construir(Brightness.dark);

  static ThemeData _construir(Brightness brillo) {
    final base = ThemeData(
      colorScheme: ColorScheme.fromSeed(seedColor: _semilla, brightness: brillo),
      useMaterial3: true,
    );
    return base.copyWith(
      appBarTheme: base.appBarTheme.copyWith(centerTitle: false),
      cardTheme: base.cardTheme.copyWith(
        elevation: 0,
        margin: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
    );
  }
}
