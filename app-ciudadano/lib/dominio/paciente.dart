import 'package:flutter/foundation.dart';

/// El sexo que consta en el laboratorio, que es lo que decide qué rango de referencia aplica.
enum SexoRegistrado {
  /// Mujer.
  mujer,

  /// Hombre.
  hombre,

  /// No consta, o consta como otro valor. **No se adivina.**
  noConsta,
}

/// La persona que está mirando sus resultados.
@immutable
final class Paciente {
  /// Construye el paciente.
  const Paciente({
    required this.id,
    required this.nombreDePila,
    required this.apellidos,
    this.sexo = SexoRegistrado.noConsta,
  });

  /// El id lógico del recurso `Patient` en el laboratorio.
  final String id;

  /// El nombre de pila, ya unido si viene en varias partes.
  final String nombreDePila;

  /// El nombre familiar **completo**, tal y como lo publica el laboratorio.
  ///
  /// Entero y sin tocar. La tentación es partirlo por el primer espacio para separar «los dos
  /// apellidos», y con «de la Torre Gómez» eso produce «de» y «la Torre Gómez». Cuando los dos
  /// apellidos hacen falta por separado existen las extensiones estándar, que solo están cuando el
  /// dato vino separado de origen.
  final String apellidos;

  /// El sexo registrado, o [SexoRegistrado.noConsta].
  final SexoRegistrado sexo;

  /// Nombre y apellidos como se escriben en España.
  String get nombreCompleto =>
      [nombreDePila, apellidos].where((parte) => parte.isNotEmpty).join(' ');
}
