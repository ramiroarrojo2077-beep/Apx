package ar.rama.ai.motor

/** Un paso del razonamiento del asistente, para el modo pensar. */
data class PasoAsistente(val titulo: String, val detalle: String)

/**
 * Con qué se respaldó la respuesta.
 *
 * Es la diferencia entre un dato y una suposición del modelo, y el usuario
 * tiene derecho a verla: sin esto, las dos llegan escritas con la misma
 * seguridad.
 */
enum class Respaldo(val etiqueta: String, val explicacion: String) {
    CALCULO("dato calculado", "lo resolvió una habilidad, no el modelo: es exacto"),
    WEB("con fuentes web", "está respaldado por las páginas que consulté"),
    BASE("de mi base", "sale de mi enciclopedia, escrita a mano"),
    SOLO_MODELO("sin respaldo", "sale sólo de la memoria del modelo: puede estar inventado"),
}

/** Lo que termina contestando, con de dónde salió cada cosa. */
data class RespuestaAsistente(
    val texto: String,
    val fuente: String,
    val pasos: List<PasoAsistente>,
    val fuentesWeb: List<Resultado> = emptyList(),
    val respaldo: Respaldo = Respaldo.SOLO_MODELO,
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
    var modo: Modo = Modos.PREDETERMINADO
    /** Interruptor general del usuario; cada modo además decide si le sirve. */
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
                return RespuestaAsistente(salida, "comando", pasos, emptyList(), Respaldo.CALCULO)
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
        val contextoLocal = rama.recuperar(pregunta, maximo = 3)
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
            paso("Sin modelo", "no hay modelo cargado: sólo puedo usar la base y las habilidades")

            // Sólo se contesta con la base cuando la coincidencia es franca.
            // Servir una coincidencia floja es peor que admitir que no se sabe:
            // fue lo que hizo que una pregunta sobre elecciones recibiera la
            // definición de átomo.
            val deLaBase = if (datoExacto != null) null
            else rama.recuperar(pregunta, maximo = 1, umbral = Rama.UMBRAL_ALTO).firstOrNull()

            val texto: String
            val respaldo: Respaldo
            when {
                datoExacto != null -> {
                    texto = datoExacto
                    respaldo = Respaldo.CALCULO
                }
                deLaBase != null -> {
                    texto = deLaBase
                    respaldo = Respaldo.BASE
                }
                resultados.isNotEmpty() -> {
                    // Sin modelo no puedo redactar, pero sí mostrar lo encontrado.
                    texto = resumenDeBusqueda(resultados)
                    respaldo = Respaldo.WEB
                }
                else -> {
                    texto = SIN_MODELO
                    respaldo = Respaldo.SOLO_MODELO
                }
            }
            paso("Respaldo", "${respaldo.etiqueta}: ${respaldo.explicacion}")
            alFragmento(texto)
            rama.memoria.registrarTurno("rama", texto)
            return RespuestaAsistente(texto, "sin-modelo", pasos, resultados, respaldo)
        }

        val conversacion = armarConversacion(pregunta, historial, datoExacto, contextoLocal, resultados)
        paso(
            "Generación",
            "el modelo escribe la respuesta con ${conversacion.size - 1} mensajes de contexto\n" + motor.info
        )

        // El razonamiento del modelo se saca del texto y se guarda aparte:
        // en el chat va la respuesta, no el borrador.
        val filtro = FiltroPensamiento()
        val construida = StringBuilder()
        motor.responder(
            conversacion = conversacion,
            maxTokens = modo.maxTokens,
            temperatura = modo.temperatura,
            topP = modo.topP,
            topK = modo.topK,
        ) { fragmento ->
            val visible = filtro.procesar(fragmento)
            if (visible.isNotEmpty()) {
                construida.append(visible)
                alFragmento(visible)
            }
            !filtro.terminado
        }
        val cola = filtro.cerrar()
        if (cola.isNotEmpty()) {
            construida.append(cola)
            alFragmento(cola)
        }
        if (filtro.pensado.isNotEmpty()) {
            paso("Razonamiento del modelo", filtro.pensado)
        }

        val texto = construida.toString().trim().ifEmpty {
            datoExacto ?: "Me quedé sin palabras. Probá preguntarlo de otra forma."
        }
        val respaldo = when {
            datoExacto != null -> Respaldo.CALCULO
            resultados.isNotEmpty() -> Respaldo.WEB
            contextoLocal.isNotEmpty() -> Respaldo.BASE
            else -> Respaldo.SOLO_MODELO
        }
        paso("Respaldo", "${respaldo.etiqueta}: ${respaldo.explicacion}")
        rama.memoria.registrarTurno("rama", texto)
        return RespuestaAsistente(texto, "modelo", pasos, resultados, respaldo)
    }

    fun cancelar() {
        generador?.cancelar()
    }

    /** Sin modelo que redacte, al menos se entrega lo que trajo la búsqueda. */
    fun resumenDeBusqueda(resultados: List<Resultado>): String = buildString {
        append("No tengo un modelo cargado para redactarte una respuesta, ")
        append("pero busqué en la web y encontré esto:\n\n")
        resultados.forEachIndexed { i, resultado ->
            append("${i + 1}. ${resultado.titulo}\n")
            if (resultado.resumen.isNotBlank()) append("${resultado.resumen}\n")
            append("\n")
        }
        append("Las fuentes están abajo. Si descargás un modelo, en vez de la lista ")
        append("te doy la respuesta redactada.")
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

        val sistema = StringBuilder(Modos.sistema(modo))
        if (contexto.isEmpty()) {
            // Sin fuentes, la única salida honesta es admitir la duda. Decírselo
            // explícitamente reduce bastante las invenciones con seguridad.
            sistema.append("\n\n").append(SIN_FUENTES)
        }
        val mensajes = mutableListOf(Mensaje("system", sistema.toString()))
        // Sólo los últimos turnos: el contexto del modelo es chico y caro.
        historial.takeLast(TURNOS_DE_HISTORIAL).forEach { mensajes.add(it) }

        val cuerpo = if (contexto.isEmpty()) pregunta
        else "$contexto---\nPregunta: $pregunta"
        // Qwen3 y otros modelos híbridos apagan su modo de razonamiento con
        // esta marca. Ahorra tokens; el filtro es la red por si la ignoran.
        mensajes.add(Mensaje("user", "$cuerpo $SIN_RAZONAR"))
        return mensajes
    }

    /**
     * Decide si vale la pena salir a internet, y por qué.
     *
     * Es deliberadamente generoso con las preguntas factuales: un modelo chico
     * inventa fechas y nombres con total seguridad, y traerle la fuente es lo
     * único que lo frena de verdad.
     */
    fun motivoParaBuscar(pregunta: String, sinContextoLocal: Boolean, hayDatoExacto: Boolean): String? {
        if (!buscarEnWeb || !modo.buscaEnWeb) return null
        val plano = Texto.normalizar(pregunta)
        return when {
            PIDE_BUSCAR.containsMatchIn(plano) -> "me lo pediste explícitamente"
            hayDatoExacto -> null  // ya tenemos la respuesta exacta, no gastemos datos
            NECESITA_ACTUALIDAD.containsMatchIn(plano) -> "la pregunta depende de datos actuales"
            esFactual(plano) -> "es una pregunta de datos y prefiero traer la fuente antes que confiar en la memoria del modelo"
            sinContextoLocal && plano.split(" ").size >= 3 -> "no tengo nada parecido en mi base"
            else -> null
        }
    }

    /** Preguntas que piden un hecho, no una charla ni una opinión. */
    fun esFactual(plano: String): Boolean {
        if (CHARLA.containsMatchIn(plano)) return false
        return PIDE_HECHO.containsMatchIn(plano) && plano.split(" ").size >= 3
    }

    companion object {
        const val TURNOS_DE_HISTORIAL = 6
        const val SIN_RAZONAR = "/no_think"

        val SIN_FUENTES = """
            ATENCIÓN: para esta pregunta no tenés ninguna fuente ni dato verificado.
            Respondé sólo lo que sepas con seguridad. Si la respuesta necesita una fecha,
            una cifra, un nombre propio o un hecho concreto que no recordás con certeza,
            decí que no estás seguro en lugar de arriesgar. Es preferible una respuesta
            corta y honesta que una completa e inventada.
        """.trimIndent()

        val SIN_MODELO = """
            Para contestar esto necesito el modelo de lenguaje, y todavía no hay ninguno cargado.

            Tocá el botón del modelo, arriba a la derecha, y descargá uno. Con el modelo puedo
            responder cualquier cosa que me preguntes; sin él sólo sé lo que tengo en mi base
            y lo que calculan mis habilidades, que es exacto pero acotado.
        """.trimIndent()

        private val PIDE_BUSCAR = Regex(
            "\\b(busca|buscar|buscame|googlea|fijate en internet|en la web|en internet)\\b"
        )
        /** Fórmulas con las que se pide un hecho concreto. */
        private val PIDE_HECHO = Regex(
            "\\b(quien|quienes|cuando|donde|cuantos?|cuantas?|cual|cuales|que año|en que año|" +
                "que fecha|de que|por que|como se llama|nombre de|autor de|invento|descubrio|" +
                "gano|fundo|escribio|nacio|murio|capital de|poblacion|altura de|distancia)\\b"
        )

        /** Lo que claramente no es una consulta de datos. */
        private val CHARLA = Regex(
            "\\b(hola|buenas|gracias|chau|como estas|como andas|contame un chiste|" +
                "escribi|escribime|inventa|imagina|un cuento|un poema|una historia|" +
                "que opinas|que te parece|ayudame a|traduci|resumi)\\b"
        )

        private val NECESITA_ACTUALIDAD = Regex(
            "\\b(hoy|ahora|actual|actualmente|ultimo|ultima|reciente|noticias?|precio|" +
                "cotizacion|dolar|quien es|quien gano|quien fue|cuando (sale|salio|es)|" +
                "20[2-9][0-9])\\b"
        )
    }
}
