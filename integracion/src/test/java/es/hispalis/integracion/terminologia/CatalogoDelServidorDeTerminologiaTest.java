package es.hispalis.integracion.terminologia;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El catálogo que el motor usa sale de la guía, y traduce en los dos sentidos con el mismo mapa.
 *
 * <p>Es el invariante 4 comprobado: si alguien sustituyera esto por un {@code Map<String,String>}
 * escrito a mano, los tests seguirían pasando <strong>mientras la copia coincidiera</strong> — y
 * dejarían de pasar el día que la guía añadiera una prueba, que es exactamente cuando tienen que
 * avisar.
 */
class CatalogoLeidoDeLaGuiaTest extends TestDelMotor {

    @Autowired
    private CatalogoDelLaboratorio catalogo;

    @Test
    void el_catalogo_se_carga_de_la_guia_y_no_viene_vacio() {
        assertThat(catalogo.tamano())
                .as("las 21 pruebas que publica el CodeSystem de la guía")
                .isEqualTo(21);
    }

    @Test
    void un_codigo_del_catalogo_local_se_reconoce_y_uno_inventado_no() {
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.SYSTEM, "GLU"))
                .contains("GLU");
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.SYSTEM, "INVENTADO"))
                .as("pertenecer al system propio no basta: el código tiene que estar en el catálogo")
                .isEmpty();
    }

    @Test
    void un_loinc_se_traduce_al_dialecto_local_con_el_conceptmap_de_la_guia() {
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, "2345-7"))
                .as("glucosa masa/volumen, que es la que informa este laboratorio")
                .contains("GLU");
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, "2160-0"))
                .contains("CREA");
    }

    /**
     * La urea no es el nitrógeno ureico, y confundirlas multiplica el resultado por 2,14.
     *
     * <p>El {@code ConceptMap} de la guía mapea la urea a {@code 3091-6}; {@code 3094-0} es BUN, que
     * es otra magnitud. Que este último no traduzca es la comprobación de que el motor no está
     * buscando «lo que se parezca».
     */
    @Test
    void el_nitrogeno_ureico_no_se_confunde_con_la_urea() {
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, "3091-6"))
                .contains("UREA");
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, "3094-0"))
                .as("BUN no está en el mapa, y no se aproxima")
                .isEmpty();
    }

    /**
     * Invertir un mapeo solo es legítimo donde la relación es de equivalencia.
     *
     * <p>El hematocrito se mapea con {@code source-is-broader-than-target} —el término LOINC fija el
     * método automatizado y el código local no—, así que la vuelta no vale: varios LOINC caerían en
     * el mismo código local, y elegir uno sería inventar una precisión que el mapa dice que no hay.
     * La hemoglobina, en cambio, sí es {@code equivalent} y sí se invierte.
     */
    @Test
    void una_correspondencia_que_no_es_equivalencia_no_se_invierte() {
        String loincDelHematocrito = catalogo.buscar("HTO").orElseThrow().loinc();
        assertThat(loincDelHematocrito).isEqualTo("4544-3");
        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, loincDelHematocrito))
                .as("no se invierte lo que el propio mapa dice que no es una equivalencia")
                .isEmpty();

        assertThat(catalogo.traducirALocal(CatalogoDelLaboratorio.LOINC, "718-7"))
                .as("y la hemoglobina, que sí es equivalencia, sí")
                .contains("HB");
    }

    @Test
    void la_unidad_de_una_prueba_la_declara_el_catalogo() {
        assertThat(catalogo.buscar("GLU").orElseThrow().unidad()).contains("mg/dL");
        assertThat(catalogo.buscar("K").orElseThrow().unidad()).contains("mmol/L");
    }

    @Test
    void una_prueba_cualitativa_no_declara_unidad() {
        assertThat(catalogo.buscar("LEGIOAG").orElseThrow().esCuantitativa())
                .as("el antígeno de Legionella se informa positivo o negativo, no con una cifra")
                .isFalse();
    }

    @Test
    void los_tipos_de_muestra_salen_del_valueset_de_la_guia() {
        assertThat(catalogo.esTipoDeMuestraConocido("119364003"))
                .as("suero, que es el tipo más común del laboratorio")
                .isTrue();
        assertThat(catalogo.esTipoDeMuestraConocido("000000")).isFalse();
    }
}
