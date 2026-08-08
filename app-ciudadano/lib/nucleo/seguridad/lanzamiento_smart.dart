import 'package:dio/dio.dart';

import '../errores/error_de_la_app.dart';
import 'configuracion_smart.dart';
import 'descubrimiento_smart.dart';
import 'navegador_de_autorizacion.dart';
import 'pkce.dart';
import 'sesion.dart';

/// El lanzamiento autónomo de SMART: de no tener nada a tener un testigo con paciente en contexto.
///
/// Es *standalone launch* y no *EHR launch* porque a esta app la abre la persona desde el icono de
/// su móvil, no un EHR desde una historia abierta: no hay `launch` que devolver ni `iss` que llegue
/// por parámetro. La base FHIR la trae la app configurada y de ella sale todo lo demás.
///
/// Lo que **no** hace, y es tan importante como lo que hace: no guarda ningún secreto de cliente, no
/// parsea el testigo de acceso para deducir permisos y no manda el testigo a nadie que no sea el
/// laboratorio para el que se pidió.
final class LanzamientoSmart {
  /// Construye el lanzamiento.
  ///
  /// El [http] es un cliente **sin el interceptor del testigo**: lo que se habla aquí es con el
  /// servidor de identidad, y mandarle el testigo del laboratorio sería regalárselo.
  LanzamientoSmart({
    required this.configuracion,
    required NavegadorDeAutorizacion navegador,
    required Dio http,
  }) : _navegador = navegador,
       _http = http;

  /// Con cuánta antelación se considera caducado un testigo. Cubre el viaje de la petición.
  static const Duration margenDeCaducidad = Duration(seconds: 30);

  /// Quién es esta app y contra qué laboratorio va.
  final ConfiguracionSmart configuracion;

  final NavegadorDeAutorizacion _navegador;
  final Dio _http;

  /// Recorre el flujo entero y devuelve la sesión abierta.
  Future<DatosDeSesion> entrar() async {
    final servidor = await _descubrir();
    _exigirLoQueHaceFalta(servidor);

    final verificador = Pkce.verificador();
    final estado = Pkce.estado();

    final vuelta = await _navegador.pedirAutorizacion(
      peticion: _peticionDeAutorizacion(servidor, verificador: verificador, estado: estado),
      esquemaDeRetorno: configuracion.esquemaDeRetorno,
    );

    final codigo = _codigoDe(vuelta, estadoEsperado: estado);
    final respuesta = await _canjear(servidor.testigo, {
      'grant_type': 'authorization_code',
      'code': codigo,
      'redirect_uri': configuracion.redireccion.toString(),
      // En un cliente público el `client_id` va en el cuerpo; no hay cliente que autenticar.
      'client_id': configuracion.clienteId,
      'code_verifier': verificador,
    });

    return _sesionDe(respuesta, servidor);
  }

  /// Renueva el testigo sin volver a molestar a la persona.
  ///
  /// Solo funciona mientras el servidor de identidad mantenga la sesión: **no se pide
  /// `offline_access`**, que es el permiso más sensible que una app puede solicitar y que aquí
  /// significaría poder leer la historia de alguien con el móvil en un cajón.
  ///
  /// El testigo de refresco es **de un solo uso**: el que venga en la respuesta sustituye al
  /// anterior, y al refrescar no se piden más *scopes* de los que ya había.
  Future<DatosDeSesion> renovar(DatosDeSesion sesion) async {
    final refresco = sesion.testigoDeRefresco;
    if (refresco == null) {
      throw ErrorDeLaApp.sesionCaducada();
    }

    final servidor = await _descubrir();
    final respuesta = await _canjear(servidor.testigo, {
      'grant_type': 'refresh_token',
      'refresh_token': refresco,
      'client_id': configuracion.clienteId,
    });

    return sesion.renovada(
      testigo: respuesta['access_token']! as String,
      caducaEn: _caducidad(respuesta),
      testigoDeRefresco: respuesta['refresh_token'] as String? ?? refresco,
    );
  }

  Future<DescubrimientoSmart> _descubrir() async {
    final documento = await _pedirJson(configuracion.descubrimiento);
    try {
      return DescubrimientoSmart.deJson(documento, configuracion.baseFhir);
    } on FormatException catch (fallo) {
      throw ErrorDeLaApp.laboratorio(fallo.message);
    }
  }

  /// Comprueba que el servidor da lo que esta app necesita, **antes** de mandar a nadie a ningún
  /// sitio. Descubrirlo a mitad del flujo deja a la persona en una pantalla del navegador sin
  /// entender por qué.
  void _exigirLoQueHaceFalta(DescubrimientoSmart servidor) {
    if (!servidor.admiteS256) {
      throw ErrorDeLaApp.laboratorio(
        'Este laboratorio no admite el método de seguridad que exige la app. No se puede entrar.',
      );
    }
    if (!servidor.admiteLanzamientoAutonomo || !servidor.admitePermisosDePaciente) {
      throw ErrorDeLaApp.laboratorio(
        'Este laboratorio no ofrece acceso a pacientes desde una app propia.',
      );
    }
  }

  /// Monta la petición de autorización.
  ///
  /// Va por `GET` porque quien la abre es el navegador del sistema y no hay forma de que envíe un
  /// formulario. La norma prefiere `POST` por la longitud de la cadena de *scopes*; aquí son cuatro
  /// y la URL se queda muy por debajo de cualquier límite.
  Uri _peticionDeAutorizacion(
    DescubrimientoSmart servidor, {
    required String verificador,
    required String estado,
  }) => servidor.autorizacion.replace(
    queryParameters: {
      'response_type': 'code',
      'client_id': configuracion.clienteId,
      'redirect_uri': configuracion.redireccion.toString(),
      'scope': ConfiguracionSmart.scopes,
      'state': estado,
      // Sin `aud`, un testigo legítimo de este mismo servidor de identidad valdría contra otro
      // servidor de recursos. Es lo que impide entregárselo a un laboratorio falsificado.
      'aud': configuracion.baseFhir.toString(),
      'code_challenge': Pkce.reto(verificador),
      'code_challenge_method': 'S256',
    },
  );

  /// Saca el código de la vuelta del navegador, comprobando antes el `state`.
  String _codigoDe(Uri vuelta, {required String estadoEsperado}) {
    final error = vuelta.queryParameters['error'];
    if (error != null) {
      throw ErrorDeLaApp.autorizacionRechazada(
        vuelta.queryParameters['error_description'] ??
            'El servidor de identidad no ha autorizado la entrada.',
      );
    }
    if (vuelta.queryParameters['state'] != estadoEsperado) {
      // Esta respuesta no es de la petición que hizo esta app. Se tira sin mirar el código.
      throw ErrorDeLaApp.autorizacionRechazada(
        'La respuesta de identificación no se corresponde con la solicitud. No se ha entrado.',
      );
    }
    final codigo = vuelta.queryParameters['code'];
    if (codigo == null || codigo.isEmpty) {
      throw ErrorDeLaApp.autorizacionRechazada(
        'El servidor de identidad no ha devuelto ningún código de autorización.',
      );
    }
    return codigo;
  }

  Future<DatosDeSesion> _sesionDe(
    Map<String, Object?> respuesta,
    DescubrimientoSmart servidor,
  ) async {
    // Lo CONCEDIDO, que puede no ser lo pedido: más, menos o distinto. La app se ajusta a esto.
    final concedidos = (respuesta['scope'] as String? ?? '')
        .split(' ')
        .where((s) => s.isNotEmpty)
        .toSet();

    return DatosDeSesion(
      testigo: respuesta['access_token']! as String,
      caducaEn: _caducidad(respuesta),
      scopesConcedidos: concedidos,
      testigoDeRefresco: respuesta['refresh_token'] as String?,
      // El contexto llega **con** el testigo, no dentro: es un parámetro JSON de la respuesta.
      paciente: respuesta['patient'] as String?,
      fhirUser: await _quienHaEntrado(respuesta, servidor),
    );
  }

  /// Quién ha entrado, preguntándoselo al servidor de identidad.
  ///
  /// Se usa el `userinfo` del emisor y **no se abre el `id_token`**. Verificar la firma de un JWT
  /// exigiría traerse el JWKS y hacer RSA en el cliente; preguntar por un canal TLS directo al
  /// emisor, autenticado con el testigo recién obtenido, da la misma garantía sin escribir
  /// criptografía en una app. Si el servidor no declara `sso-openid-connect`, no se pregunta.
  Future<String?> _quienHaEntrado(
    Map<String, Object?> respuesta,
    DescubrimientoSmart servidor,
  ) async {
    if (!servidor.capacidades.contains('sso-openid-connect')) {
      return null;
    }
    final emisor = servidor.emisor;
    if (emisor == null) {
      return null;
    }

    try {
      final oidc = await _pedirJson(emisor.resolve('.well-known/openid-configuration'));
      final donde = oidc['userinfo_endpoint'];
      if (donde is! String) {
        return null;
      }
      final quien = await _pedirJson(
        Uri.parse(donde),
        cabeceras: {'Authorization': 'Bearer ${respuesta['access_token']}'},
      );
      return quien['fhirUser'] as String?;
    } on ErrorDeLaApp {
      // Saber el nombre es un adorno; no saberlo no impide consultar los resultados.
      return null;
    }
  }

  static DateTime _caducidad(Map<String, Object?> respuesta) {
    final segundos = respuesta['expires_in'];
    return DateTime.now().add(
      Duration(seconds: segundos is num ? segundos.toInt() : 300),
    );
  }

  Future<Map<String, Object?>> _canjear(Uri donde, Map<String, String> cuerpo) async {
    try {
      final respuesta = await _http.postUri<Map<String, Object?>>(
        donde,
        data: cuerpo,
        options: Options(contentType: Headers.formUrlEncodedContentType),
      );
      return respuesta.data ?? const {};
    } on DioException catch (fallo) {
      throw _traducir(fallo, 'No se ha podido completar la identificación.');
    }
  }

  Future<Map<String, Object?>> _pedirJson(Uri donde, {Map<String, String>? cabeceras}) async {
    try {
      final respuesta = await _http.getUri<Map<String, Object?>>(
        donde,
        options: Options(headers: cabeceras),
      );
      return respuesta.data ?? const {};
    } on DioException catch (fallo) {
      throw _traducir(fallo, 'El laboratorio no ha contestado a la consulta de configuración.');
    }
  }

  static ErrorDeLaApp _traducir(DioException fallo, String siNoSeSabeMas) {
    if (fallo.response == null) {
      return ErrorDeLaApp.sinRed();
    }
    final cuerpo = fallo.response!.data;
    final descripcion = cuerpo is Map
        ? cuerpo['error_description'] ?? cuerpo['error']
        : null;
    return ErrorDeLaApp.autorizacionRechazada(
      descripcion is String ? descripcion : siNoSeSabeMas,
    );
  }
}
