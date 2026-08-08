import 'package:flutter/foundation.dart';

import 'informe.dart';
import 'paciente.dart';

/// Todo lo que la pantalla necesita de una persona, traído de una vez.
@immutable
final class Historial {
  /// Construye el historial.
  const Historial({required this.paciente, required this.informes});

  /// La ficha de la persona. De ella sale el sexo, que decide qué rango de referencia aplica.
  final Paciente paciente;

  /// Sus analíticas, de la más reciente a la más antigua.
  final List<Informe> informes;
}

/// De dónde salen los datos de la persona.
///
/// El `ViewModel` depende de esta interfaz y no del cliente FHIR: así la pantalla se prueba sin
/// levantar un laboratorio, y el día que cambie el borde —otra versión de FHIR, otro transporte— no
/// hay que tocar la presentación.
abstract interface class RepositorioDeInformes {
  /// Trae la ficha y las analíticas de [pacienteId].
  ///
  /// Es una sola operación y no dos porque el rango de referencia que se enseña depende del sexo de
  /// la persona: separarlas dejaría abierta la puerta a pintar un resultado antes de saber con qué
  /// se compara.
  Future<Historial> historial(String pacienteId);
}
