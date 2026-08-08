import 'package:flutter_test/flutter_test.dart';
import 'package:hispalis_ciudadano/dominio/resultado.dart';
import 'package:hispalis_ciudadano/presentacion/formato.dart';

void main() {
  group('Valores', () {
    test('un número se enseña con su unidad, nunca solo', () {
      expect(Formato.valor(const ValorNumerico(13.4, 'g/dL')), '13,4 g/dL');
    });

    test('a la española: coma decimal y sin ceros de relleno', () {
      expect(Formato.valor(const ValorNumerico(5, 'mmol/L')), '5 mmol/L');
      expect(Formato.valor(const ValorNumerico(0.75, 'mg/dL')), '0,75 mg/dL');
    });

    test('un resultado cualitativo se enseña tal cual lo dice el laboratorio', () {
      expect(Formato.valor(const ValorTextual('No se observan')), 'No se observan');
    });

    test('sin valor se dice que no hay valor, no se deja el hueco', () {
      expect(Formato.valor(const SinValor()), 'Sin resultado');
      expect(Formato.valor(const SinValor()), isNotEmpty);
    });
  });

  group('Rangos de referencia', () {
    test('un rango con dos límites se lee de un vistazo', () {
      expect(
        Formato.rango(const RangoDeReferencia(unidad: 'mmol/L', bajo: 3.5, alto: 5.1)),
        '3,5 – 5,1 mmol/L',
      );
    });

    test('los rangos abiertos se dicen con palabras', () {
      expect(Formato.rango(const RangoDeReferencia(unidad: 'mg/dL', alto: 200)), 'hasta 200 mg/dL');
      expect(Formato.rango(const RangoDeReferencia(unidad: 'mg/dL', bajo: 40)), 'desde 40 mg/dL');
    });

    test('sin rango publicado se DICE, no se deja en blanco', () {
      // Un guion o un hueco se leen como «se me ha olvidado». Y un hueco en la línea del rango,
      // justo debajo de una cifra, invita a compararla con la nada.
      expect(Formato.rango(null), 'No consta rango de referencia para esta prueba');
      expect(Formato.rango(const RangoDeReferencia(unidad: 'g/dL')), Formato.sinRango);
    });

    test('un rango sin unidad no arrastra un espacio suelto al final', () {
      expect(Formato.rango(const RangoDeReferencia(unidad: '', bajo: 1, alto: 2)), '1 – 2');
    });
  });

  group('Fechas', () {
    test('día y hora, que dos analíticas del mismo día son lo normal', () {
      expect(Formato.fechaYHora(DateTime(2026, 8, 8, 9, 30)), '08/08/2026 09:30');
      expect(Formato.fecha(DateTime(2026, 8, 8, 9, 30)), '08/08/2026');
    });
  });
}
