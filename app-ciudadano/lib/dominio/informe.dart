import 'package:flutter/foundation.dart';

import 'resultado.dart';

/// Una analítica, vista desde el lado de la persona.
///
/// Hay dos clases y la diferencia no es cosmética: un informe **emitido** lo ha firmado un
/// facultativo y ya no cambia; una analítica **en curso** son resultados que el analizador ya ha
/// medido y que nadie ha revisado todavía. La app enseña las dos, y dice cuál es cuál. Esconder la
/// segunda haría que a la persona le faltaran resultados que existen; enseñarla sin distinguirla
/// haría que se tomara por definitiva una cifra que puede cambiar.
@immutable
final class Informe {
  /// Construye el informe.
  const Informe({
    required this.id,
    required this.titulo,
    required this.fecha,
    required this.resultados,
    required this.emitido,
  });

  /// El informe firmado que publica el laboratorio.
  factory Informe.emitido({
    required String id,
    required DateTime fecha,
    required List<Resultado> resultados,
  }) => Informe(
    id: id,
    titulo: 'Informe de laboratorio',
    fecha: fecha,
    resultados: resultados,
    emitido: true,
  );

  /// Los resultados que aún no están en ningún informe firmado.
  ///
  /// No tiene id de recurso porque no lo es: es una agrupación que hace la app con lo que el
  /// laboratorio todavía no ha cerrado.
  factory Informe.enCurso({required DateTime fecha, required List<Resultado> resultados}) =>
      Informe(
        id: 'en-curso',
        titulo: 'Analítica en curso',
        fecha: fecha,
        resultados: resultados,
        emitido: false,
      );

  /// El id lógico del `DiagnosticReport`, o `en-curso` para la agrupación de lo pendiente.
  final String id;

  /// Cómo se llama en la lista.
  final String titulo;

  /// Cuándo se emitió, o la medición más reciente si todavía no se ha emitido.
  final DateTime fecha;

  /// Las determinaciones que lleva dentro.
  final List<Resultado> resultados;

  /// Si el laboratorio lo ha emitido como informe.
  final bool emitido;

  /// Si **todo** lo que hay dentro lo ha firmado un facultativo.
  ///
  /// Se mira resultado a resultado y no solo el estado del informe: son dos afirmaciones distintas,
  /// y la que le importa a quien lee una cifra concreta es la de esa cifra.
  bool get validadoPorFacultativo =>
      emitido && resultados.every((r) => r.estado.loFirmaUnFacultativo);

  /// Cuántos resultados siguen sin revisar.
  int get pendientesDeValidar =>
      resultados.where((r) => !r.estado.loFirmaUnFacultativo).length;
}
