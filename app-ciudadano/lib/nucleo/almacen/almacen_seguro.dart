import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Dónde se guardan el testigo y lo poco que se recuerda entre arranques.
///
/// Es un puerto y no la librería directamente por dos razones. La primera es de prueba: los tests
/// no pueden hablar con el Keystore de Android. La segunda importa más — con un puerto, **el resto
/// de la app no tiene forma de escribir un testigo en otro sitio**: no hay un `SharedPreferences`
/// al alcance de la mano al que caiga alguien con prisa.
abstract interface class AlmacenSeguro {
  /// Guarda [valor] bajo [clave], sobrescribiendo lo que hubiera.
  Future<void> guardar(String clave, String valor);

  /// Devuelve lo guardado bajo [clave], o `null` si no hay nada.
  Future<String?> leer(String clave);

  /// Borra **todo** lo de esta app. Es lo que se ejecuta al cerrar sesión.
  Future<void> borrarTodo();
}

/// El almacén cifrado que da la plataforma: Keystore en Android, Keychain en iOS.
///
/// Nada de esto se guarda en `SharedPreferences` ni en un fichero de la app: un testigo de acceso a
/// una historia clínica es una credencial, y una credencial en claro en el disco del dispositivo
/// está a un backup sin cifrar de estar en el ordenador de cualquiera.
final class AlmacenDeLaPlataforma implements AlmacenSeguro {
  /// Construye el almacén sobre `flutter_secure_storage`.
  const AlmacenDeLaPlataforma([
    this._almacen = const FlutterSecureStorage(
      // Que el dato no se pueda leer con el dispositivo bloqueado, y que no salga en la copia de
      // seguridad a iCloud: un testigo restaurado en otro teléfono es un testigo entregado.
      iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock_this_device),
    ),
  ]);

  final FlutterSecureStorage _almacen;

  @override
  Future<void> guardar(String clave, String valor) => _almacen.write(key: clave, value: valor);

  @override
  Future<String?> leer(String clave) => _almacen.read(key: clave);

  @override
  Future<void> borrarTodo() => _almacen.deleteAll();
}
