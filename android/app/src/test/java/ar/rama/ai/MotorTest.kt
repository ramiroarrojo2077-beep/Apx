package ar.rama.ai

import ar.rama.ai.motor.AlmacenEnMemoria
import ar.rama.ai.motor.Calculadora
import ar.rama.ai.motor.Corrector
import ar.rama.ai.motor.ErrorCalculo
import ar.rama.ai.motor.Memoria
import ar.rama.ai.motor.Rama
import ar.rama.ai.motor.Texto
import ar.rama.ai.motor.Vectorizador
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests del motor de Rama. Corren en la JVM: no necesitan emulador. */
class MotorTest {

    private fun conocimiento(): String {
        // data/conocimiento.json es la única fuente de verdad; Gradle la copia
        // a los assets al construir, así que acá la leemos del repositorio.
        val candidatos = listOf(
            "../../data/conocimiento.json",
            "data/conocimiento.json",
            "build/generated/assets/conocimiento.json",
        )
        val archivo = candidatos.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("no encuentro conocimiento.json desde ${File(".").absolutePath}")
        return archivo.readText()
    }

    private fun nuevaRama() = Rama(conocimiento(), Memoria(AlmacenEnMemoria()), semilla = 7L)

    // ------------------------------------------------------------- texto

    @Test
    fun normalizarQuitaTildesYSignos() {
        assertEquals("como estas", Texto.normalizar("¿Cómo estás?"))
        assertEquals("hola rama", Texto.normalizar("¡Hola,   Rama!"))
    }

    @Test
    fun normalizarConservaLaEnie() {
        assertEquals("añejo niño", Texto.normalizar("Añejo NIÑO"))
    }

    @Test
    fun raizAgrupaVariantes() {
        assertEquals(Texto.raiz("programar"), Texto.raiz("programando"))
        assertEquals(Texto.raiz("computadora"), Texto.raiz("computadoras"))
    }

    @Test
    fun raizRespetaPalabrasCortas() {
        assertEquals("sol", Texto.raiz("sol"))
        assertEquals("casa", Texto.raiz("casa"))
    }

    @Test
    fun tokenizarQuitaVacias() {
        assertFalse(Texto.tokenizar("la capital de francia").contains("de"))
    }

    @Test
    fun tokenizarNoVaciaFrasesDePurasStopwords() {
        assertTrue(Texto.tokenizar("como estas").isNotEmpty())
    }

    // -------------------------------------------------------- corrección

    @Test
    fun transposicionCuestaUnaEdicion() {
        assertEquals(1, Corrector.distanciaEdicion("pyhton", "python"))
    }

    @Test
    fun corteTemprano() {
        assertTrue(Corrector.distanciaEdicion("abc", "xyzw", 1) > 1)
    }

    @Test
    fun corrigeContraElVocabulario() {
        val c = Corrector().entrenar(listOf("que es python", "contame un chiste"))
        assertEquals("que es python", c.corregir("que es pyhton"))
    }

    @Test
    fun noTocaPalabrasLejanas() {
        val c = Corrector().entrenar(listOf("que es python"))
        assertTrue(c.corregir("zapallo").contains("zapallo"))
    }

    // ------------------------------------------------------ vectorizador

    @Test
    fun vectorNormalizado() {
        val v = Vectorizador().entrenar(listOf("hola como estas", "que es python"))
        val norma = v.vectorizar("que es python").values.sumOf { it * it }
        assertEquals(1.0, norma, 1e-6)
    }

    @Test
    fun identicoDaUno() {
        val v = Vectorizador().entrenar(listOf("hola como estas", "que es python"))
        val vec = v.vectorizar("que es python")
        assertEquals(1.0, Vectorizador.similitud(vec, vec), 1e-6)
    }

    @Test
    fun textoVacio() {
        val v = Vectorizador().entrenar(listOf("hola"))
        assertTrue(v.vectorizar("   ").isEmpty())
        assertEquals(0.0, Vectorizador.similitud(emptyMap(), v.vectorizar("hola")), 1e-9)
    }

    @Test
    fun elMasParecidoGana() {
        val docs = listOf("hola como estas", "que es python", "contame un chiste")
        val v = Vectorizador().entrenar(docs)
        val q = v.vectorizar("contame un chiste porfa")
        val ganador = docs.maxByOrNull { Vectorizador.similitud(q, v.vectorizar(it)) }
        assertEquals("contame un chiste", ganador)
    }

    // ------------------------------------------------------- calculadora

    @Test
    fun operaciones() {
        assertEquals(4.0, Calculadora.evaluar("2+2"), 1e-9)
        assertEquals(16.0, Calculadora.evaluar("(3+5)*2"), 1e-9)
        assertEquals(1024.0, Calculadora.evaluar("2**10"), 1e-9)
        assertEquals(-5.0, Calculadora.evaluar("-10 + 5"), 1e-9)
        assertEquals(14.285714, Calculadora.evaluar("100/7"), 1e-5)
    }

    @Test(expected = ErrorCalculo::class)
    fun divisionPorCero() {
        Calculadora.evaluar("1/0")
    }

    @Test(expected = ErrorCalculo::class)
    fun rechazaTextoArbitrario() {
        Calculadora.evaluar("borrar todo")
    }

    @Test(expected = ErrorCalculo::class)
    fun rechazaExponenteGigante() {
        Calculadora.evaluar("9^999999")
    }

    // ----------------------------------------------------------- memoria

    @Test
    fun persisteEntreInstancias() {
        val almacen = AlmacenEnMemoria()
        val m = Memoria(almacen)
        m.aprender("capital de francia", "París")
        m.recordar("nombre", "Ramiro")
        val otra = Memoria(almacen)
        assertEquals("París", otra.aprendido[0].respuesta)
        assertEquals("Ramiro", otra.recuerdo("nombre"))
    }

    @Test
    fun aprenderActualizaSinDuplicar() {
        val m = Memoria(AlmacenEnMemoria())
        m.aprender("color", "verde")
        m.aprender("Color", "azul")
        assertEquals(1, m.aprendido.size)
        assertEquals("azul", m.aprendido[0].respuesta)
    }

    @Test
    fun olvidar() {
        val m = Memoria(AlmacenEnMemoria())
        m.aprender("x", "y")
        assertTrue(m.olvidar("x"))
        assertFalse(m.olvidar("x"))
    }

    @Test
    fun memoriaCorruptaNoRompe() {
        val m = Memoria(AlmacenEnMemoria("{esto no es json"))
        assertTrue(m.aprendido.isEmpty())
    }

    // ------------------------------------------------------------- rama

    @Test
    fun intencionesBasicas() {
        val ia = nuevaRama()
        val casos = mapOf(
            "hola" to "saludo",
            "chau" to "despedida",
            "quien sos" to "identidad",
            "que es python" to "python",
            "contame un chiste" to "chiste",
            "muchas gracias" to "gracias",
        )
        for ((entrada, intencion) in casos) {
            assertEquals("fallo con «$entrada»", intencion, ia.responder(entrada).intencion)
        }
    }

    @Test
    fun toleraErratas() {
        assertEquals("python", nuevaRama().responder("que es pyhton").intencion)
    }

    @Test
    fun admiteNoSaber() {
        val r = nuevaRama().responder("cual es el pib de mongolia en 1997")
        assertEquals("fallback", r.fuente)
        assertTrue(r.confianza < Rama.UMBRAL_BAJO)
    }

    @Test
    fun aprendeYRecupera() {
        val ia = nuevaRama()
        ia.responder("aprende: capital de francia = París")
        val r = ia.responder("cual es la capital de francia")
        assertEquals("París", r.texto)
        assertEquals("aprendido", r.fuente)
    }

    @Test
    fun aprendePorCorreccion() {
        val ia = nuevaRama()
        ia.responder("cual es mi comida favorita")
        ia.responder("responde: milanesas")
        assertEquals("milanesas", ia.responder("cual es mi comida favorita").texto)
    }

    @Test
    fun olvida() {
        val ia = nuevaRama()
        ia.responder("aprende: x = y")
        ia.responder("olvidá x")
        assertEquals("fallback", ia.responder("x").fuente)
    }

    @Test
    fun recuerdaElNombre() {
        val ia = nuevaRama()
        ia.responder("me llamo Ramiro")
        assertTrue(ia.responder("como me llamo").texto.contains("Ramiro"))
    }

    @Test
    fun skillsGananAlConocimiento() {
        val r = nuevaRama().responder("cuanto es 12*7")
        assertEquals("skill", r.fuente)
        assertTrue(r.texto.contains("84"))
    }

    @Test
    fun calculaEnCastellano() {
        assertTrue(nuevaRama().responder("2 mas 2").texto.contains("4"))
        assertTrue(nuevaRama().responder("el 15% de 200").texto.contains("30"))
        assertTrue(nuevaRama().responder("raiz de 144").texto.contains("12"))
    }

    @Test
    fun tiraDadoDeVeinte() {
        val texto = nuevaRama().responder("tira un dado de 20").texto
        assertTrue("respuesta inesperada: $texto", texto.contains("20 caras"))
    }

    @Test
    fun entradaVacia() {
        assertEquals("vacio", nuevaRama().responder("   ").intencion)
    }

    @Test
    fun noRepiteLaMismaRespuestaSeguida() {
        val ia = nuevaRama()
        val vistas = (1..6).map { ia.responder("hola").texto }.toSet()
        assertTrue(vistas.size > 1)
    }

    // ------------------------------------------------------ modo pensar

    @Test
    fun dejaTrazaDeRazonamiento() {
        val pasos = nuevaRama().responder("que es python").pasos
        val titulos = pasos.map { it.titulo }
        assertTrue(titulos.contains("Normalización"))
        assertTrue(titulos.contains("Similitud coseno"))
        assertTrue(titulos.contains("Decisión"))
        assertTrue(pasos.all { it.detalle.isNotBlank() })
    }

    @Test
    fun laTrazaMuestraLaCorreccion() {
        val paso = nuevaRama().responder("que es pyhton").pasos
            .first { it.titulo == "Corrección de erratas" }
        assertTrue("no muestra la corrección: ${paso.detalle}", paso.detalle.contains("python"))
    }

    @Test
    fun lasSkillsTambienDejanTraza() {
        val pasos = nuevaRama().responder("cuanto es 2*3").pasos
        assertTrue(pasos.any { it.titulo == "Habilidades" && it.detalle.contains("calculadora") })
    }

    @Test
    fun candidatosOrdenadosDeMejorAPeor() {
        val candidatos = nuevaRama().responder("que es la inteligencia artificial").candidatos
        assertTrue(candidatos.size >= 2)
        assertTrue(candidatos[0].puntaje >= candidatos[1].puntaje)
        assertNotEquals(0.0, candidatos[0].puntaje)
    }
}
