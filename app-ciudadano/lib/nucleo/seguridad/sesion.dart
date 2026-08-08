import 'dart:convert';

import 'package:flutter/foundation.dart';

import '../almacen/almacen_seguro.dart';

/// Lo que la app sabe de quien la está usando, mientras dura la sesión.
///
/// **No se guarda ni un dato clínico.** La caché clínica local «mínima imprescindible» que piden las
/// convenciones resultó ser **ninguna**: un resultado preliminar se convierte en validado y una cifra
/// puede corregirse, así que enseñar una copia de ayer como si fuera de ahora es un riesgo clínico
/// que no compensa el ahorro de una petición. Lo único que sobrevive al cierre de la app son las
/// credenciales, y viven en el almacén cifrado de la plataforma.
@immutable
final class DatosDeSesion {
  /// Construye la sesión con lo que devolvió el servidor de identidad.
  const DatosDeSesion({
    required this.testigo,
    required this.caducaEn,
    required this.scopesConcedidos,
    this.testigoDeRefresco,
    this.paciente,
    this.fhirUser,
  });

  /// Rehidrata la sesión de lo guardado en el almacén seguro.
  factory DatosDeSesion.deJson(Map<String, Object?> json) => DatosDeSesion(
    testigo: json['testigo']! as String,
    caducaEn: DateTime.parse(json['caducaEn']! as String),
    scopesConcedidos: (json['scopes']! as String).split(' ').where((s) => s.isNotEmpty).toSet(),
    testigoDeRefresco: json['refresco'] as String?,
    paciente: json['paciente'] as String?,
    fhirUser: json['fhirUser'] as String?,
  );

  /// El testigo de acceso. Se trata como **opaco**: no se parsea para deducir permisos.
  final String testigo;

  /// Cuándo deja de valer el testigo de acceso.
  final DateTime caducaEn;

  /// Lo que el servidor **concedió**, que puede no ser lo que se pidió.
  final Set<String> scopesConcedidos;

  /// El testigo de refresco, si lo hubo. De un solo uso: al gastarlo se sustituye por el nuevo.
  final String? testigoDeRefresco;

  /// El id del paciente en contexto (`launch/patient`), suelto y sin el tipo delante.
  final String? paciente;

  /// La referencia FHIR de quien ha entrado, tal cual la manda el servidor: `Patient/<id>`.
  final String? fhirUser;

  /// De quién son los datos que esta sesión puede pedir.
  ///
  /// Se prefiere el contexto de lanzamiento y se cae al `fhirUser` cuando es un paciente, que es lo
  /// que ocurre en un lanzamiento autónomo desde una app de ciudadano. **No es control de acceso**:
  /// quien decide de quién es cada recurso es el laboratorio. Aquí solo sirve para no pedir a
  /// ciegas y para saber a quién se está enseñando la pantalla.
  String? get pacienteEnContexto {
    if (paciente != null && paciente!.isNotEmpty) {
      return paciente;
    }
    final usuario = fhirUser;
    if (usuario != null && usuario.startsWith('Patient/')) {
      return usuario.substring('Patient/'.length);
    }
    return null;
  }

  /// Si el testigo ya no vale, con un margen para no usarlo justo en el filo.
  bool caducadaCon(Duration margen) => DateTime.now().add(margen).isAfter(caducaEn);

  /// Serializa la sesión para el almacén seguro.
  Map<String, Object?> aJson() => {
    'testigo': testigo,
    'caducaEn': caducaEn.toIso8601String(),
    'scopes': scopesConcedidos.join(' '),
    'refresco': testigoDeRefresco,
    'paciente': paciente,
    'fhirUser': fhirUser,
  };

  /// Copia la sesión sustituyendo el testigo, su caducidad y el de refresco.
  DatosDeSesion renovada({
    required String testigo,
    required DateTime caducaEn,
    required String? testigoDeRefresco,
  }) => DatosDeSesion(
    testigo: testigo,
    caducaEn: caducaEn,
    scopesConcedidos: scopesConcedidos,
    testigoDeRefresco: testigoDeRefresco,
    paciente: paciente,
    fhirUser: fhirUser,
  );
}

/// La sesión abierta, observable por la interfaz.
///
/// Es el `ChangeNotifier` del patrón MVVM: las pantallas miran si hay sesión y quién es, y no
/// tocan el almacén.
final class Sesion extends ChangeNotifier {
  /// Construye la sesión sobre el almacén cifrado de la plataforma.
  Sesion(this._almacen);

  /// La clave bajo la que vive la sesión en el almacén seguro.
  static const String clave = 'hispalis.sesion';

  final AlmacenSeguro _almacen;

  DatosDeSesion? _datos;

  /// Los datos de la sesión, o `null` si no hay ninguna abierta.
  DatosDeSesion? get datos => _datos;

  /// Si hay sesión y su testigo todavía vale.
  bool get activa => _datos != null && !_datos!.caducadaCon(Duration.zero);

  /// Recupera la sesión guardada, si la hubiera. Se llama una vez, al arrancar.
  Future<void> recordar() async {
    final guardada = await _almacen.leer(clave);
    if (guardada == null) {
      return;
    }
    _datos = DatosDeSesion.deJson(jsonDecode(guardada) as Map<String, Object?>);
    notifyListeners();
  }

  /// Abre la sesión y la persiste cifrada.
  Future<void> abrir(DatosDeSesion datos) async {
    _datos = datos;
    await _almacen.guardar(clave, jsonEncode(datos.aJson()));
    notifyListeners();
  }

  /// Cierra la sesión y **borra todo** lo que la app tuviera guardado.
  ///
  /// `borrarTodo` y no borrar la clave de la sesión: si algún día se guarda algo más, cerrar sesión
  /// tiene que llevárselo por delante sin que nadie se acuerde de añadirlo aquí.
  Future<void> cerrar() async {
    _datos = null;
    await _almacen.borrarTodo();
    notifyListeners();
  }
}
