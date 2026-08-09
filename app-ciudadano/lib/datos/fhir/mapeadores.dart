import '../../dominio/informe.dart';
import '../../dominio/paciente.dart';
import '../../dominio/resultado.dart';

/// De los recursos FHIR R5 que publica el laboratorio a lo que la app entiende.
///
/// Es el borde: aquí se acaba el JSON y empieza el dominio. Nada de lo de abajo sube: si mañana el
/// laboratorio publicara R6, este fichero sería el único que habría que reescribir.
abstract final class MapeadorFhir {
  /// El código SNOMED con el que el laboratorio dice a qué sexo aplica un rango de referencia.
  static const Map<SexoRegistrado, String> sexoEnSnomed = {
    SexoRegistrado.hombre: '248153007',
    SexoRegistrado.mujer: '248152002',
  };

  /// Convierte un `Patient` en la ficha de la persona.
  static Paciente paciente(Map<String, Object?> recurso) {
    final nombre = _primero(recurso['name']);
    return Paciente(
      id: recurso['id'] as String? ?? '',
      nombreDePila: (_lista(nombre?['given'])).whereType<String>().join(' '),
      // Entero, tal cual. Nunca se parte por el espacio.
      apellidos: nombre?['family'] as String? ?? '',
      sexo: switch (recurso['gender']) {
        'female' => SexoRegistrado.mujer,
        'male' => SexoRegistrado.hombre,
        _ => SexoRegistrado.noConsta,
      },
    );
  }

  /// Convierte un `Observation` en un resultado, eligiendo el rango que aplica a [sexo].
  static Resultado resultado(Map<String, Object?> recurso, SexoRegistrado sexo) {
    final codigo = recurso['code'] as Map<String, Object?>? ?? const {};
    return Resultado(
      id: recurso['id'] as String? ?? '',
      // El nombre en español lo pone la terminología del laboratorio; la app no traduce ni inventa.
      prueba: _nombreDePrueba(codigo),
      valor: _valor(recurso),
      rango: _rangoQueAplica(recurso, sexo),
      estado: switch (recurso['status']) {
        'preliminary' || 'registered' => EstadoDelResultado.preliminar,
        'final' => EstadoDelResultado.validado,
        'corrected' || 'amended' => EstadoDelResultado.corregido,
        'cancelled' || 'entered-in-error' => EstadoDelResultado.anulado,
        _ => EstadoDelResultado.desconocido,
      },
      medidoEn: _fecha(recurso['effectiveDateTime']),
      porQueExiste: _porQueExiste(recurso),
    );
  }

  /// La frase que explica por qué existe una determinación que nadie pidió (R5: `triggeredBy`).
  ///
  /// Se coge `reason` tal cual y **no se compone nada** cuando falta: sin la frase del laboratorio,
  /// lo único que la app sabría decir es «derivada de otra», que a una persona no le aclara nada y
  /// sí le añade una pregunta. La pantalla, entonces, no enseña la línea.
  static String? _porQueExiste(Map<String, Object?> recurso) {
    final disparos = recurso['triggeredBy'] as List<Object?>? ?? const [];
    if (disparos.isEmpty) {
      return null;
    }
    final motivo = (disparos.first as Map<String, Object?>?)?['reason'] as String?;
    return motivo == null || motivo.trim().isEmpty ? null : motivo.trim();
  }

  /// Convierte un `DiagnosticReport` en un informe, cogiendo sus resultados de [porReferencia].
  ///
  /// Los que no estén en el mapa se omiten en silencio, porque eso es exactamente lo que significa:
  /// el laboratorio **omite del resultado de búsqueda** lo que la sesión no puede ver, en vez de
  /// contestar cuántos hay. Reventar aquí convertiría una decisión de consentimiento del servidor en
  /// un error de la app.
  static Informe informe(Map<String, Object?> recurso, Map<String, Resultado> porReferencia) =>
      Informe.emitido(
        id: recurso['id'] as String? ?? '',
        fecha: _fecha(recurso['issued']) ?? _fecha(recurso['effectiveDateTime']) ?? DateTime.now(),
        resultados: _lista(recurso['result'])
            .whereType<Map<String, Object?>>()
            .map((referencia) => porReferencia[referencia['reference']])
            .whereType<Resultado>()
            .toList(),
      );

  static String _nombreDePrueba(Map<String, Object?> codigo) {
    final texto = codigo['text'];
    if (texto is String && texto.isNotEmpty) {
      return texto;
    }
    final display = _lista(
      codigo['coding'],
    ).whereType<Map<String, Object?>>().map((c) => c['display']).whereType<String>().firstOrNull;
    return display ?? 'Prueba sin nombre';
  }

  static ValorDeResultado _valor(Map<String, Object?> recurso) {
    final cantidad = recurso['valueQuantity'];
    if (cantidad is Map<String, Object?>) {
      final cifra = cantidad['value'];
      // `unit` es lo que el laboratorio imprime; `code` es el UCUM, que está para convertir. Si
      // faltaran las dos, esto NO es un número: sin unidad, la cifra no significa nada.
      final unidad = (cantidad['unit'] ?? cantidad['code']) as String?;
      if (cifra is num && unidad != null && unidad.isNotEmpty) {
        return ValorNumerico(cifra.toDouble(), unidad);
      }
    }
    final texto = recurso['valueString'];
    if (texto is String && texto.isNotEmpty) {
      return ValorTextual(texto);
    }
    final codificado = recurso['valueCodeableConcept'];
    if (codificado is Map<String, Object?>) {
      final dicho = _nombreDePrueba(codificado);
      return ValorTextual(dicho);
    }
    return const SinValor();
  }

  /// Elige el rango de la persona: el de su sexo si el laboratorio lo publica, y si no el común.
  ///
  /// Con el sexo sin constar se devuelve **solo** el común, nunca uno de los específicos: enseñarle
  /// el rango de hombre a alguien de quien no se sabe el sexo es inventarse un dato clínico. En la
  /// serie roja esa diferencia cambia la lectura del resultado.
  static RangoDeReferencia? _rangoQueAplica(Map<String, Object?> recurso, SexoRegistrado sexo) {
    final rangos = _lista(recurso['referenceRange']).whereType<Map<String, Object?>>().toList();
    final codigo = sexoEnSnomed[sexo];

    final suyo = codigo == null
        ? null
        : rangos.where((rango) => _aplicaA(rango, codigo)).firstOrNull;
    final comun = rangos.where((rango) => _lista(rango['appliesTo']).isEmpty).firstOrNull;

    final elegido = suyo ?? comun;
    if (elegido == null) {
      return null;
    }

    final bajo = elegido['low'] as Map<String, Object?>?;
    final alto = elegido['high'] as Map<String, Object?>?;
    final unidad = (bajo?['unit'] ?? alto?['unit'] ?? bajo?['code'] ?? alto?['code']) as String?;
    return RangoDeReferencia(
      unidad: unidad ?? '',
      bajo: (bajo?['value'] as num?)?.toDouble(),
      alto: (alto?['value'] as num?)?.toDouble(),
    );
  }

  static bool _aplicaA(Map<String, Object?> rango, String codigoDelSexo) =>
      _lista(rango['appliesTo']).whereType<Map<String, Object?>>().any(
        (poblacion) => _lista(poblacion['coding']).whereType<Map<String, Object?>>().any(
          (codificacion) => codificacion['code'] == codigoDelSexo,
        ),
      );

  static Map<String, Object?>? _primero(Object? valor) =>
      _lista(valor).whereType<Map<String, Object?>>().firstOrNull;

  static List<Object?> _lista(Object? valor) => valor is List<Object?> ? valor : const [];

  static DateTime? _fecha(Object? valor) =>
      valor is String ? DateTime.tryParse(valor)?.toLocal() : null;
}
