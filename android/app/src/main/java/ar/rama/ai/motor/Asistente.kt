package ar.rama.ai.motor

/** Un paso del razonamiento del asistente, para el modo pensar. */
data class PasoAsistente(val titulo: String, val detalle: String)

/** Lo que termina contestando, con de dónde salió cada cosa. */
data class RespuestaAsistente(
    val texto: String,
    val fuente: String,
    val pasos: List<PasoAsistente>,
    val fuentesWeb: List<Resultado> = emptyList(),
)

/**
 * Une las tres cabezas de Rama.
 *
 * El modelo de lenguaje redacta, pero no trabaja solo: las habilidades
 * deterministas le ponen los números exactos, la base local le da lo que
 * Rama ya sabe, y la búsqueda web le trae lo que no puede saber. El modelo
 * es el que escribe; los otros tres son los que evitan que invente.
 */
class Asistente(
    val rama: Rama,
    private val buscador: Buscador = Buscador(),
) {

    var generador: Generador? = null
    var buscarEnWeb: Boolean = true

    fun responder(
        pregunta: String,
        historial: List<Mensaje> = emptyList(),
        alPaso: (PasoAsistente) -> Unit = {},
        alFragmento: (String) -> Boolean,
    ): RespuestaAsistente {
        val pasos = mutableListOf<PasoAsistente>()
        // Los pasos se emiten mientras ocurren: en el modo pensar se ven
        // aparecer, que es la única forma de que sirvan de algo.
        fun paso(titulo: String, detalle: String) {
            val nuevo = PasoAsistente(titulo, detalle)
            pasos.add(nuevo)
            alPaso(nuevo)
        }

        rama.memoria.registrarTurno("usuario", pregunta)

        // 1. Los comandos son órdenes, no preguntas: se ejecutan y punto.
        for ((nombre, comando) in Skills.COMANDOS) {
            val salida = comando(pregunta, rama)
            if (salida != null) {
                paso("Comando", "«$nombre»: es una orden directa, no hace falta el modelo")
                alFragmento(salida)
                rama.memoria.registrarTurno("rama", salida)
                return RespuestaAsistente(salida, "comando", pasos)
            }
        }

        // 2. Datos exactos. Un modelo chico multiplica mal; una habilidad no.
        var datoExacto: String? = null
        for ((nombre, habilidad) in Skills.TODAS) {
            val salida = habilidad(pregunta, rama)
            if (salida != null) {
                datoExacto = salida
                paso("Dato exacto", "la habilidad «$nombre» resolvió: $salida")
                break
            }
        }

        // 3. Lo que Rama ya sabe de su propia base.
        val contextoLocal = rama.recuperar(pregunta, maximo = 2)
        if (contextoLocal.isNotEmpty()) {
            paso("Base local", "${contextoLocal.size} fragmentos de mi base se parecen a tu pregunta")
        }

        // 4. La web, sólo cuando hace falta.
        var resultados: List<Resultado> = emptyList()
        val motivoBusqueda = motivoParaBuscar(pregunta, contextoLocal.isEmpty(), datoExacto != null)
        if (motivoBusqueda != null) {
            paso("Búsqueda web", "$motivoBusqueda — consultando DuckDuckGo")
            resultados = buscador.buscar(pregunta)
            paso(
                "Resultados",
                if (resultados.isEmpty()) "sin resultados (¿hay conexión?)"
                else resultados.joinToString("\n") { "· ${it.titulo}\n  ${it.url}" },
            )
        }

        // 5. Generar. Sin modelo cargado, Rama vuelve a ser la de antes.
        val motor = generador
        if (motor == null) {
            paso("Sin modelo", "no hay modelo cargado: respondo con la base y las habilidades")
            val respaldo = datoExacto ?: rama.responder(pregunta).texto
            alFragmento(respaldo)
            return RespuestaAsistente(respaldo, "sin-modelo", pasos, resultados)
        }

        val conversacion = armarConversacion(pregunta, historial, datoExacto, contextoLocal, resultados)
        paso(
            "Generación",
            "el modelo escribe la respuesta con ${conversacion.size - 1} mensajes de contexto\n" + motor.info
        )

        val construida = StringBuilder()
        motor.responder(conversacion) { fragmento ->
            construida.append(fragmento)
            alFragmento(fragmento)
        }

        val texto = construida.toString().trim().ifEmpty {
            datoExacto ?: "Me quedé sin palabras. Probá preguntarlo de otra forma."
        }
        rama.memoria.registrarTurno("rama", texto)
        return RespuestaAsistente(texto, "modelo", pasos, resultados)
    }

    fun cancelar() {
        generador?.cancelar()
    }

    /** Arma los mensajes que ve el modelo, con el contexto por delante. */
    fun armarConversacion(
        pregunta: String,
        historial: List<Mensaje>,
        datoExacto: String?,
        contextoLocal: List<String>,
        resultados: List<Resultado>,
    ): List<Mensaje> {
        val contexto = StringBuilder()
        if (datoExacto != null) {
            contexto.append("DATO VERIFICADO (calculado por mí, es correcto):\n$datoExacto\n\n")
        }
        if (contextoLocal.isNotEmpty()) {
            contexto.append("DE MI BASE DE CONOCIMIENTO:\n")
            contextoLocal.forEach { contexto.append("- $it\n") }
            contexto.append("\n")
        }
        if (resultados.isNotEmpty()) {
            contexto.append("RESULTADOS DE BÚSQUEDA WEB (de hoy):\n")
            resultados.forEachIndexed { i, r ->
                contexto.append("[${i + 1}] ${r.titulo}\n${r.resumen}\nFuente: ${r.url}\n\n")
            }
        }

        val mensajes = mutableListOf(Mensaje("system", SISTEMA))
        // Sólo los últimos turnos: el contexto del modelo es chico y caro.
        historial.takeLast(TURNOS_DE_HISTORIAL).forEach { mensajes.add(it) }

        val cuerpo = if (contexto.isEmpty()) pregunta
        else "$contexto---\nPregunta: $pregunta"
        mensajes.add(Mensaje("user", cuerpo))
        return mensajes
    }

    /** Decide si vale la pena salir a internet, y por qué. */
    fun motivoParaBuscar(pregunta: String, sinContextoLocal: Boolean, hayDatoExacto: Boolean): String? {
        if (!buscarEnWeb) return null
        val plano = Texto.normalizar(pregunta)
        return when {
            PIDE_BUSCAR.containsMatchIn(plano) -> "me lo pediste explícitamente"
            hayDatoExacto -> null  // ya tenemos la respuesta exacta, no gastemos datos
            NECESITA_ACTUALIDAD.containsMatchIn(plano) -> "la pregunta depende de datos actuales"
            sinContextoLocal && plano.split(" ").size >= 3 -> "no tengo nada parecido en mi base"
            else -> null
        }
    }

    companion object {
        const val TURNOS_DE_HISTORIAL = 6

        val SISTEMA = """
            Sos Rama, una IA que corre entera dentro del teléfono del usuario.
            Respondé siempre en español rioplatense, de forma directa y breve.
            Si te dan un DATO VERIFICADO, usalo tal cual: es correcto.
            Si te dan RESULTADOS DE BÚSQUEDA, respondé con eso y citá la fuente entre corchetes.
            Si no sabés algo, decilo. Nunca inventes fechas, cifras, nombres ni enlaces.
        """.trimIndent()

        private val PIDE_BUSCAR = Regex(
            "\\b(busca|buscar|buscame|googlea|fijate en internet|en la web|en internet)\\b"
        )
        private val NECESITA_ACTUALIDAD = Regex(
            "\\b(hoy|ahora|actual|actualmente|ultimo|ultima|reciente|noticias?|precio|" +
                "cotizacion|dolar|quien es|quien gano|quien fue|cuando (sale|salio|es)|" +
                "20[2-9][0-9])\\b"
        )
    }
}
