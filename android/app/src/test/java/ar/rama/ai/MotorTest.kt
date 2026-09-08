package ar.rama.ai

import ar.rama.ai.motor.AlmacenEnMemoria
import ar.rama.ai.motor.Asistente
import ar.rama.ai.motor.Buscador
import ar.rama.ai.motor.Catalogo
import ar.rama.ai.motor.Descargador
import ar.rama.ai.motor.FiltroPensamiento
import ar.rama.ai.motor.Modos
import ar.rama.ai.motor.Generador
import ar.rama.ai.motor.Mensaje
import ar.rama.ai.motor.Resultado
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ------------------------------------------------- habilidades de datos

    @Test
    fun sabeCapitales() {
        val ia = nuevaRama()
        assertTrue(ia.responder("cual es la capital de francia").texto.contains("París"))
        assertTrue(ia.responder("capital de japon").texto.contains("Tokio"))
        assertTrue(ia.responder("cual es la capital del peru").texto.contains("Lima"))
        assertTrue(ia.responder("capital de eeuu").texto.contains("Washington"))
    }

    @Test
    fun sabeDeQuePaisEsUnaCapital() {
        assertTrue(nuevaRama().responder("de que pais es capital roma").texto.contains("Italia"))
    }

    @Test
    fun convierteUnidades() {
        val ia = nuevaRama()
        assertTrue(ia.responder("cuantos km son 5 millas").texto.contains("8.04"))
        assertTrue(ia.responder("cuantas millas son 100 km").texto.contains("62.13"))
        assertTrue(ia.responder("5 kg en libras").texto.contains("11.02"))
    }

    @Test
    fun convierteTemperaturas() {
        val respuesta = nuevaRama().responder("20 grados celsius a fahrenheit").texto
        assertTrue("respuesta inesperada: $respuesta", respuesta.contains("68"))
    }

    @Test
    fun resuelveFechas() {
        val ia = nuevaRama()
        assertTrue(ia.responder("que dia cae el 25 de diciembre de 2027").texto.contains("sábado"))
        assertTrue(ia.responder("cuantos dias faltan para navidad").texto.contains("diciembre"))
    }

    @Test
    fun operaSobreTexto() {
        val ia = nuevaRama()
        assertTrue(ia.responder("cuantas letras tiene murcielago").texto.contains("10"))
        assertTrue(ia.responder("python al reves").texto.contains("nohtyp"))
        assertTrue(ia.responder("neuquen es palindromo").texto.startsWith("Sí"))
    }

    @Test
    fun loEnsenadoLeGanaALasHabilidades() {
        val ia = nuevaRama()
        ia.responder("aprende: capital de francia = Lyon, según yo")
        val r = ia.responder("cual es la capital de francia")
        assertEquals("Lyon, según yo", r.texto)
        assertEquals("aprendido", r.fuente)
    }

    @Test
    fun olvidarDevuelveElControlALaHabilidad() {
        val ia = nuevaRama()
        ia.responder("aprende: capital de francia = Lyon")
        ia.responder("olvidá capital de francia")
        assertTrue(ia.responder("cual es la capital de francia").texto.contains("París"))
    }

    @Test
    fun laBaseAmpliadaResponde() {
        val ia = nuevaRama()
        val casos = mapOf(
            "que es internet" to "que_es_internet",
            "por que el cielo es azul" to "por_que_cielo_azul",
            "como cuido la bateria" to "bateria_celular",
            "guardas mis datos" to "guardas_datos",
            "estoy aburrido" to "aburrido",
        )
        for ((entrada, intencion) in casos) {
            assertEquals("fallo con «$entrada»", intencion, ia.responder(entrada).intencion)
        }
    }

    @Test
    fun elFallbackSugiereTemasCercanos() {
        val r = nuevaRama().responder("asdkjh qwerty zxcvb")
        assertEquals("fallback", r.fuente)
        assertTrue("no orienta al usuario: ${r.texto}", r.texto.contains("responde:"))
    }

    // ------------------------------------------- asistente generativo

    private fun nuevoAsistente() = Asistente(nuevaRama())

    @Test
    fun sinModeloElAsistenteSigueRespondiendo() {
        val asistente = nuevoAsistente()
        asistente.buscarEnWeb = false
        val partes = StringBuilder()
        val r = asistente.responder("cual es la capital de francia") { partes.append(it); true }
        assertEquals("sin-modelo", r.fuente)
        assertTrue("no usó la habilidad: ${r.texto}", r.texto.contains("París"))
        assertEquals(r.texto, partes.toString())
    }

    @Test
    fun losComandosNoNecesitanModelo() {
        val asistente = nuevoAsistente()
        asistente.buscarEnWeb = false
        val r = asistente.responder("aprende: mi perro = Cachito") { true }
        assertEquals("comando", r.fuente)
        assertTrue(r.texto.contains("Cachito"))
    }

    @Test
    fun buscaSoloCuandoTieneSentido() {
        val asistente = nuevoAsistente()
        assertNotNull("debería buscar si se lo piden",
            asistente.motivoParaBuscar("busca quien gano el partido", false, false))
        assertNotNull("debería buscar lo que depende de hoy",
            asistente.motivoParaBuscar("cual es el precio del dolar hoy", false, false))
        assertNull("no debe gastar datos si ya tiene el dato exacto",
            asistente.motivoParaBuscar("cuanto es 2 mas 2", false, true))
        assertNull("no debe buscar lo que ya sabe",
            asistente.motivoParaBuscar("que es python", false, false))
    }

    @Test
    fun noBuscaSiEstaApagado() {
        val asistente = nuevoAsistente()
        asistente.buscarEnWeb = false
        assertNull(asistente.motivoParaBuscar("busca lo que sea hoy", true, false))
    }

    @Test
    fun elPromptLlevaElContextoPorDelante() {
        val asistente = nuevoAsistente()
        val conversacion = asistente.armarConversacion(
            pregunta = "quien ganó?",
            historial = listOf(Mensaje("user", "hola"), Mensaje("assistant", "¡hola!")),
            datoExacto = "2+2 = 4",
            contextoLocal = listOf("Python es un lenguaje."),
            resultados = listOf(Resultado("Título", "https://ejemplo.com", "Resumen")),
        )
        assertEquals("system", conversacion.first().rol)
        val ultimo = conversacion.last()
        assertEquals("user", ultimo.rol)
        assertTrue("falta el dato exacto", ultimo.contenido.contains("DATO VERIFICADO"))
        assertTrue("falta la base local", ultimo.contenido.contains("Python es un lenguaje"))
        assertTrue("falta la fuente web", ultimo.contenido.contains("https://ejemplo.com"))
        assertTrue("se perdió la pregunta", ultimo.contenido.contains("quien ganó?"))
        assertTrue("se perdió el historial", conversacion.any { it.contenido == "hola" })
    }

    @Test
    fun elHistorialSeRecortaAlUltimoTramo() {
        val asistente = nuevoAsistente()
        val largo = (1..30).map { Mensaje("user", "mensaje $it") }
        val conversacion = asistente.armarConversacion("y?", largo, null, emptyList(), emptyList())
        // sistema + los últimos turnos + la pregunta
        assertEquals(Asistente.TURNOS_DE_HISTORIAL + 2, conversacion.size)
        assertTrue(conversacion.any { it.contenido == "mensaje 30" })
        assertFalse(conversacion.any { it.contenido == "mensaje 1" })
    }

    // ---------------------------------------------------- búsqueda web

    @Test
    fun elParserDeBusquedaExtraeLosResultados() {
        val html = """
            <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fes.wikipedia.org%2Fwiki%2FPar%C3%ADs&amp;rut=x">
              Par&iacute;s - <b>Wikipedia</b>
            </a>
            <a class="result__snippet" href="x">La capital de Francia.</a>
        """.trimIndent()
        val resultados = Buscador.parsear(html)
        assertEquals(1, resultados.size)
        assertEquals("https://es.wikipedia.org/wiki/París", resultados[0].url)
        assertEquals("París - Wikipedia", resultados[0].titulo)
        assertEquals("La capital de Francia.", resultados[0].resumen)
    }

    @Test
    fun elParserToleraHtmlDesconocido() {
        assertTrue(Buscador.parsear("<html><body>cambió todo</body></html>").isEmpty())
    }

    @Test
    fun decodificaEntidadesYEnlaces() {
        assertEquals("café", Buscador.limpiar("caf&#233;"))
        assertEquals("España", Buscador.limpiar("Espa&ntilde;a"))
        assertEquals("https://a.com/x", Buscador.descifrarEnlace("https://a.com/x"))
    }

    // ------------------------------------------------- modelo y descarga

    @Test
    fun elCatalogoOfreceLosDosModelos() {
        assertEquals(2, Catalogo.MODELOS.size)
        assertNotNull(Catalogo.porId("qwen3-0.6b"))
        assertNotNull(Catalogo.porId("qwen3-1.7b"))
        assertTrue(Catalogo.MODELOS.all { it.repositorio.isNotBlank() && it.archivo.endsWith(".gguf") })
        assertTrue(Catalogo.MODELOS[0].bytesAproximados < Catalogo.MODELOS[1].bytesAproximados)
    }

    @Test
    fun reconoceUnGgufPorSuFirma() {
        val bueno = File.createTempFile("modelo", ".gguf").apply {
            writeBytes("GGUF".toByteArray() + ByteArray(64))
            deleteOnExit()
        }
        val malo = File.createTempFile("pagina", ".html").apply {
            writeText("<html>404</html>")
            deleteOnExit()
        }
        assertTrue(Descargador.esGguf(bueno))
        assertFalse(Descargador.esGguf(malo))
    }

    @Test
    fun sinLibreriaNativaNoSeAbreNingunModelo() {
        // En la JVM de tests no está el .so: abrir debe devolver null, no romperse.
        val inexistente = File("/no/existe/modelo.gguf")
        assertNull(Generador.abrir(inexistente))
    }

    @Test
    fun losHilosRecomendadosSonRazonables() {
        val hilos = Generador.hilosRecomendados()
        assertTrue("hilos fuera de rango: $hilos", hilos in 2..6)
    }

    // ------------------------------------------- pensamiento y modos

    private fun filtrar(fragmentos: List<String>): Pair<String, String> {
        val filtro = FiltroPensamiento()
        val visible = StringBuilder()
        fragmentos.forEach { visible.append(filtro.procesar(it)) }
        visible.append(filtro.cerrar())
        return visible.toString() to filtro.pensado
    }

    @Test
    fun sacaElPensamientoDelTexto() {
        val (visible, pensado) = filtrar(listOf("<think>debo responder X</think>La capital es París."))
        assertEquals("La capital es París.", visible)
        assertEquals("debo responder X", pensado)
    }

    @Test
    fun aguantaLaEtiquetaPartidaEntreTokens() {
        val (visible, pensado) = filtrar(listOf("<th", "ink>", "pen", "sando", "</thi", "nk>", "Hola", " che"))
        assertEquals("Hola che", visible)
        assertEquals("pensando", pensado)
    }

    @Test
    fun noTocaElTextoLimpio() {
        val (visible, pensado) = filtrar(listOf("Respuesta ", "normal."))
        assertEquals("Respuesta normal.", visible)
        assertEquals("", pensado)
    }

    @Test
    fun noSeComeLosMenorQue() {
        assertEquals("2 < 3 y 4 > 1", filtrar(listOf("2 < 3 y ", "4 > 1")).first)
    }

    @Test
    fun elPensamientoSinCerrarNoSeFiltra() {
        val (visible, pensado) = filtrar(listOf("<think>me quedé pensando y no cerré"))
        assertEquals("", visible)
        assertTrue(pensado.contains("no cerré"))
    }

    @Test
    fun elPensamientoLlegaTokenATokenIgual() {
        val tokens = "<think>\nBusco.\n</think>\n\nEs París.".map { it.toString() }
        assertEquals("Es París.", filtrar(tokens).first.trim())
    }

    @Test
    fun todosLosModosPidenEspanol() {
        assertTrue(Modos.BASE.contains("español rioplatense"))
        assertTrue("no prohíbe otros idiomas", Modos.BASE.contains("Nunca contestes en"))
        assertTrue("no prohíbe mostrar el razonamiento", Modos.BASE.contains("No muestres tu razonamiento"))
        for (modo in Modos.TODOS) {
            val sistema = Modos.sistema(modo)
            assertTrue("«${modo.nombre}» no lleva la base", sistema.contains("español rioplatense"))
            assertTrue("«${modo.nombre}» no lleva su instrucción", sistema.contains(modo.instruccion))
        }
    }

    @Test
    fun losModosSonDistintosEntreSi() {
        assertEquals(5, Modos.TODOS.size)
        assertEquals(Modos.TODOS.size, Modos.TODOS.map { it.id }.toSet().size)
        val temperaturas = Modos.TODOS.map { it.temperatura }
        assertTrue("las temperaturas no varían", temperaturas.toSet().size > 1)
        assertTrue(Modos.CREATIVO.temperatura > Modos.PRECISO.temperatura)
        assertTrue(Modos.AL_HUESO.maxTokens < Modos.EXPLICAR.maxTokens)
        assertTrue(Modos.TODOS.all { it.temperatura in 0.1f..1.2f && it.maxTokens >= 100 })
    }

    @Test
    fun elModoDesconocidoCaeEnElPredeterminado() {
        assertEquals(Modos.PREDETERMINADO.id, Modos.porId("no-existe").id)
        assertEquals(Modos.PREDETERMINADO.id, Modos.porId(null).id)
        assertEquals("preciso", Modos.porId("preciso").id)
    }

    @Test
    fun elModoCreativoNoSaleABuscar() {
        val asistente = nuevoAsistente()
        asistente.modo = Modos.CREATIVO
        assertNull(asistente.motivoParaBuscar("busca lo que sea hoy", true, false))
        asistente.modo = Modos.PRECISO
        assertNotNull(asistente.motivoParaBuscar("busca lo que sea hoy", true, false))
    }

    @Test
    fun elModoDefineElMensajeDeSistema() {
        val asistente = nuevoAsistente()
        asistente.modo = Modos.AL_HUESO
        val conversacion = asistente.armarConversacion("hola", emptyList(), null, emptyList(), emptyList())
        assertEquals("system", conversacion.first().rol)
        assertTrue(conversacion.first().contenido.contains(Modos.AL_HUESO.instruccion))
        assertTrue("falta apagar el razonamiento del modelo",
            conversacion.last().contenido.contains(Asistente.SIN_RAZONAR))
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
