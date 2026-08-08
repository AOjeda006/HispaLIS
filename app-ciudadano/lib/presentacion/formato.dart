import 'package:intl/intl.dart';

import '../dominio/resultado.dart';

/// Cómo se escriben en pantalla una cifra, un rango y una fecha.
///
/// Está en un solo sitio porque **presentar mal un resultado es un error clínico, no estético**. Una
/// cifra sin unidad no se puede interpretar y un rango en blanco hace creer que la prueba no tiene
/// referencia. Las dos funciones de aquí devuelven siempre algo legible: no hay forma de que la
/// pantalla se quede con el hueco vacío.
abstract final class Formato {
  /// Lo que se enseña cuando el laboratorio no publica rango para esa prueba.
  ///
  /// Se dice, no se calla. Un guion o un hueco en blanco se leen como «se me ha olvidado».
  static const String sinRango = 'No consta rango de referencia para esta prueba';

  /// Lo que se enseña cuando el resultado no trae valor.
  static const String sinValor = 'Sin resultado';

  static final NumberFormat _numero = NumberFormat.decimalPatternDigits(
    locale: 'es_ES',
    decimalDigits: 2,
  )..minimumFractionDigits = 0;

  static final DateFormat _fechaYHora = DateFormat('dd/MM/yyyy HH:mm');
  static final DateFormat _soloFecha = DateFormat('dd/MM/yyyy');

  /// El valor con su unidad pegada, o el texto tal cual si el resultado es cualitativo.
  ///
  /// **Nunca devuelve la cifra sola.** El tipo del dominio no lo permite —una [ValorNumerico] no se
  /// construye sin unidad— y aquí tampoco hay una rama que la suelte.
  static String valor(ValorDeResultado valor) => switch (valor) {
    ValorNumerico(:final cifra, :final unidad) => '${numero(cifra)} $unidad',
    ValorTextual(:final texto) => texto,
    SinValor() => sinValor,
  };

  /// El rango de referencia escrito para leerlo, o [sinRango] si la prueba no tiene.
  static String rango(RangoDeReferencia? rango) {
    if (rango == null) {
      return sinRango;
    }
    final unidad = rango.unidad.isEmpty ? '' : ' ${rango.unidad}';
    return switch ((rango.bajo, rango.alto)) {
      (final double bajo, final double alto) => '${numero(bajo)} – ${numero(alto)}$unidad',
      (null, final double alto) => 'hasta ${numero(alto)}$unidad',
      (final double bajo, null) => 'desde ${numero(bajo)}$unidad',
      _ => sinRango,
    };
  }

  /// Un número a la española: coma decimal y sin ceros de relleno.
  static String numero(double cifra) => _numero.format(cifra);

  /// Día y hora. La hora importa: dos analíticas del mismo día son lo normal.
  static String fechaYHora(DateTime cuando) => _fechaYHora.format(cuando);

  /// Solo el día, para los encabezados de la lista.
  static String fecha(DateTime cuando) => _soloFecha.format(cuando);
}
