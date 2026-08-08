/// Los dobles de prueba compartidos por toda la batería.
///
/// Están en un fichero aparte para que cada test hable de lo suyo y no de cómo se finge una red.
library;

import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:hispalis_ciudadano/nucleo/almacen/almacen_seguro.dart';
import 'package:hispalis_ciudadano/nucleo/errores/error_de_la_app.dart';
import 'package:hispalis_ciudadano/nucleo/seguridad/navegador_de_autorizacion.dart';

/// Un almacén en memoria que hace de Keystore/Keychain en los tests.
///
/// Guarda lo mismo que el de verdad y, sobre todo, **olvida lo mismo**: es lo que permite comprobar
/// que cerrar sesión no deja el testigo detrás.
final class AlmacenDeMentira implements AlmacenSeguro {
  /// Lo guardado, visible para que el test lo inspeccione.
  final Map<String, String> contenido = {};

  @override
  Future<void> guardar(String clave, String valor) async => contenido[clave] = valor;

  @override
  Future<String?> leer(String clave) async => contenido[clave];

  @override
  Future<void> borrarTodo() async => contenido.clear();
}

/// Un navegador que no abre nada y devuelve la vuelta que le digan.
final class NavegadorDeMentira implements NavegadorDeAutorizacion {
  /// Construye el doble. [respuesta] recibe la petición y decide qué se devuelve.
  NavegadorDeMentira(this.respuesta);

  /// Qué contesta el «navegador» a la petición de autorización.
  final Uri Function(Uri peticion) respuesta;

  /// La última petición que se le pidió abrir. Es lo que se inspecciona para ver `aud` y PKCE.
  Uri? ultimaPeticion;

  @override
  Future<Uri> pedirAutorizacion({
    required Uri peticion,
    required String esquemaDeRetorno,
  }) async {
    ultimaPeticion = peticion;
    return respuesta(peticion);
  }
}

/// Un navegador que se comporta como una persona que cierra la pestaña.
final class NavegadorQueCancela implements NavegadorDeAutorizacion {
  /// Construye el doble.
  const NavegadorQueCancela();

  @override
  Future<Uri> pedirAutorizacion({required Uri peticion, required String esquemaDeRetorno}) async =>
      throw ErrorDeLaApp.autorizacionRechazada('Has cancelado la identificación.');
}

/// Una respuesta preparada del servidor de mentira.
final class RespuestaPreparada {
  /// Construye la respuesta.
  const RespuestaPreparada(this.cuerpo, {this.estado = 200});

  /// El JSON que se devuelve.
  final Object cuerpo;

  /// El código HTTP.
  final int estado;
}

/// Un `Dio` que no sale a la red: contesta según el camino que se le pida.
///
/// Se prefiere a un `mock` generado porque lo que hay que comprobar aquí es **qué se manda por el
/// cable** —el `aud`, el `code_challenge`, el `code_verifier`— y eso se ve mejor guardando la
/// petición entera que verificando llamadas a un objeto.
final class RedDeMentira implements HttpClientAdapter {
  /// Construye la red con la tabla de respuestas por camino.
  RedDeMentira(this.respuestas);

  /// De camino (`/fhir/.well-known/smart-configuration`) a lo que se contesta.
  final Map<String, RespuestaPreparada> respuestas;

  /// Todas las peticiones que ha recibido, en orden.
  final List<RequestOptions> peticiones = [];

  /// Los cuerpos de las peticiones `POST`, ya interpretados como formulario.
  final List<Map<String, String>> formularios = [];

  /// Monta un [Dio] enchufado a esta red.
  Dio comoDio([BaseOptions? opciones]) => Dio(opciones ?? BaseOptions())..httpClientAdapter = this;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    peticiones.add(options);
    if (options.data is Map) {
      formularios.add(
        (options.data as Map).map((clave, valor) => MapEntry('$clave', '$valor')),
      );
    }

    final preparada = respuestas[options.uri.path];
    if (preparada == null) {
      return ResponseBody.fromString(
        jsonEncode({'resourceType': 'OperationOutcome'}),
        404,
        headers: _cabeceras,
      );
    }
    return ResponseBody.fromString(
      jsonEncode(preparada.cuerpo),
      preparada.estado,
      headers: _cabeceras,
    );
  }

  @override
  void close({bool force = false}) {}

  static const Map<String, List<String>> _cabeceras = {
    Headers.contentTypeHeader: ['application/json'],
  };
}
