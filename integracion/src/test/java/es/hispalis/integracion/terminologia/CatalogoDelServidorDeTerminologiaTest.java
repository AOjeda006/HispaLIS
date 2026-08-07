package es.hispalis.integracion.terminologia;

import static org.assertj.core.api.Assertions.assertThat;

import es.hispalis.integracion.TestDelMotor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * El catálogo que el motor usa sale del servidor de terminología, no de ningún fichero suyo.
 *
 * <p>Es el invariante 4 comprobado: si alguien sustituyera esto por un {@code Map<String,String>}
 * escrito a mano, los tests seguirían pasando <strong>mientras la copia coincidiera</strong> — y
 * dejarían de pasar el día que la guía añadiera una prueba, que es exactamente cuando tienen que
 * avisar.
 *
 * <p>El servidor del arnés está cargado con los artefactos que produce SUSHI y responde las cuatro
 * operaciones estándar. El motor no sabe que es de prueba: le habla por HTTP igual que le hablaría al
 * HAPI del {@code compose}, que es lo que hace cierta D14.
 */
class CatalogoDelServidorDeTerminologiaTest extends TestDelMotor {

    @Autowired
    private CatalogoDelLaboratorio catalogo;

    @Test
    void el_catalogo_se_cuenta_con_expand_y_no_viene_vacio() {
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
     *
     * <p>Quien decide es el servidor, que devuelve la relación en la respuesta del {@code $translate};
     * el motor solo se niega a invertir lo que no es equivalencia.
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

    /**
     * La unidad la declara el catálogo, y llega por {@code $lookup} como propiedad del concepto.
     *
     * <p>No se deduce del nombre de la prueba ni vive escrita en el motor: el {@code CodeSystem}
     * declara una propiedad {@code unidad-ucum} y el servidor la devuelve como {@code Coding} de UCUM.
     */
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
    void el_nombre_de_la_prueba_llega_en_espanol() {
        assertThat(catalogo.buscar("GLU").orElseThrow().display()).isEqualTo("Glucosa");
        assertThat(catalogo.buscar("LEGIOAG").orElseThrow().display())
                .as("con tilde y todo: el charset del cable también cuenta")
                .isEqualTo("Antígeno de Legionella en orina");
    }

    @Test
    void los_tipos_de_muestra_se_validan_contra_el_valueset_de_la_guia() {
        assertThat(catalogo.esTipoDeMuestraConocido("119364003"))
                .as("suero, que es el tipo más común del laboratorio")
                .isTrue();
        assertThat(catalogo.esTipoDeMuestraConocido("000000")).isFalse();
    }

    @Test
    void una_prueba_que_el_catalogo_no_tiene_no_se_inventa() {
        assertThat(catalogo.buscar("NOEXISTE")).isEmpty();
    }
}
