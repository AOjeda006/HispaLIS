import 'package:flutter/foundation.dart';

/// En qué punto del circuito está un resultado.
///
/// La distinción que le importa a la persona es una sola: **si ya lo ha revisado un facultativo o
/// todavía no**. Un valor que solo ha pasado por el analizador puede cambiar, y enseñarlo como
/// definitivo es dejar que alguien tome una decisión sobre una cifra que aún no responde nadie.
enum EstadoDelResultado {
  /// Medido por el analizador y **sin revisar**. Puede cambiar.
  preliminar,

  /// Revisado y firmado por un facultativo.
  validado,

  /// Revisado, firmado y **corregido** después.
  corregido,

  /// Retirado por el laboratorio.
  anulado,

  /// El laboratorio publicó un estado que esta app no conoce. No se supone nada.
  desconocido;

  /// Si un facultativo responde ya de esta cifra.
  bool get loFirmaUnFacultativo => this == validado || this == corregido;
}

/// El valor de un resultado, que no siempre es un número.
///
/// Es una jerarquía cerrada y no un `double` opcional con una cadena al lado porque las dos formas
/// se enseñan distinto y ninguna de las dos admite quedarse a medias: una cifra sin unidad no es un
/// resultado.
@immutable
sealed class ValorDeResultado {
  /// Constructor de la jerarquía.
  const ValorDeResultado();
}

/// Una cantidad medida, **con su unidad siempre**.
///
/// La unidad no es opcional en el constructor a propósito: es la única forma de que no exista un
/// camino por el que una cifra llegue sola a la pantalla.
@immutable
final class ValorNumerico extends ValorDeResultado {
  /// Construye la cantidad.
  const ValorNumerico(this.cifra, this.unidad);

  /// La cifra medida.
  final double cifra;

  /// La unidad UCUM tal y como la imprime el laboratorio.
  final String unidad;
}

/// Un resultado que se informa con palabras: «negativo», «no se observan».
@immutable
final class ValorTextual extends ValorDeResultado {
  /// Construye el valor textual.
  const ValorTextual(this.texto);

  /// Lo que dice el laboratorio.
  final String texto;
}

/// El resultado no trae valor, y el laboratorio lo dice explícitamente.
@immutable
final class SinValor extends ValorDeResultado {
  /// Construye la ausencia de valor.
  const SinValor();
}

/// Entre qué cifras se considera normal esta prueba **para esta persona**.
@immutable
final class RangoDeReferencia {
  /// Construye el rango.
  const RangoDeReferencia({required this.unidad, this.bajo, this.alto});

  /// Límite inferior, si la prueba lo tiene.
  final double? bajo;

  /// Límite superior, si la prueba lo tiene.
  final double? alto;

  /// La unidad del rango, que es la misma en la que se informa el valor.
  final String unidad;
}

/// Una determinación: qué se midió, cuánto salió y con qué se compara.
@immutable
final class Resultado {
  /// Construye el resultado.
  const Resultado({
    required this.id,
    required this.prueba,
    required this.valor,
    required this.estado,
    this.rango,
    this.medidoEn,
  });

  /// El id lógico del `Observation`.
  final String id;

  /// El nombre de la prueba **en español**, tal y como lo publica el laboratorio.
  ///
  /// No se traduce aquí ni se mantiene una tabla de nombres en la app: el `display` viene resuelto
  /// contra el servidor de terminología, que es la única autoridad sobre cómo se llama cada prueba.
  final String prueba;

  /// Lo que salió.
  final ValorDeResultado valor;

  /// El rango aplicable, o `null` si la prueba no tiene rango publicado (las cualitativas).
  ///
  /// Que sea `null` **no autoriza a dejar el hueco en blanco**: la pantalla dice que no consta.
  final RangoDeReferencia? rango;

  /// Si ya lo ha firmado un facultativo.
  final EstadoDelResultado estado;

  /// Cuándo se midió.
  final DateTime? medidoEn;
}
