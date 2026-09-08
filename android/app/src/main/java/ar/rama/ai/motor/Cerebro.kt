package ar.rama.ai.motor

import kotlin.math.round
import kotlin.random.Random
import org.json.JSONObject

/** Un paso del razonamiento, para el modo pensar. */
data class Paso(val titulo: String, val detalle: String)

/** Una intención candidata con su puntaje de similitud. */
data class Candidato(val intencion: String, val puntaje: Double)

/** Lo que Rama contesta, con la trazabilidad de cómo lo decidió. */
data class Respuesta(
    val texto: String,
    val intencion: String = "desconocida",
    val confianza: Double = 0.0,
    val fuente: String = "fallback",
    val candidatos: List<Candidato> = emptyList(),
    val pasos: List<Paso> = emptyList(),
)

private class Entrada(val patron: String, val intencion: String, val fuente: String) {
    var vector: Map<String, Double> = emptyMap()
}

/**
 * El cerebro de Rama: habilidades deterministas + recuperación por similitud.
 *
 * No depende de nada de Android, así que corre igual en la app y en la JVM.
 */
class Rama(
    private val conocimientoJson: String,
    val memoria: Memoria = Memoria(),
    semilla: Long? = null,
    enciclopediaJson: String? = null,
) {

    /** Los miles de temas de búsqueda directa. Vacía si no se cargó el asset. */
    val enciclopedia: Enciclopedia? = enciclopediaJson?.let { Enciclopedia(it) }

    val azar: Random = if (semilla == null) Random.Default else Random(semilla)
    private val vectorizador = Vectorizador()
    private val intenciones = LinkedHashMap<String, List<String>>()
    private val entradas = mutableListOf<Entrada>()
    private val ultimaRespuesta = HashMap<String, Int>()

    /** País (normalizado) -> nombre para mostrar y capital. */
    var capitales: Map<String, Pair<String, String>> = emptyMap()
        private set

    /** Formas alternativas de nombrar un país: "eeuu" -> "estados unidos". */
    var alias: Map<String, String> = emptyMap()
        private set

    val totalIntenciones: Int get() = intenciones.size
    val totalPatrones: Int get() = entradas.size
    val tamanioVocabulario: Int get() = vectorizador.tamanioVocabulario

    init {
        reindexar()
    }

    /**
     * (Re)construye la base y reentrena el vectorizador.
     * Se llama al arrancar y cada vez que Rama aprende u olvida algo.
     */
    fun reindexar() {
        intenciones.clear()
        entradas.clear()

        try {
            val raiz = JSONObject(conocimientoJson)
            indexarPaises(raiz.optJSONObject("paises"))
            val lista = raiz.optJSONArray("intenciones")
            if (lista != null) {
                for (i in 0 until lista.length()) {
                    val intencion = lista.getJSONObject(i)
                    val id = intencion.getString("id")
                    val respuestas = intencion.optJSONArray("respuestas")
                    intenciones[id] = (0 until (respuestas?.length() ?: 0))
                        .map { respuestas!!.getString(it) }
                    val patrones = intencion.optJSONArray("patrones")
                    for (p in 0 until (patrones?.length() ?: 0)) {
                        entradas.add(Entrada(patrones!!.getString(p), id, "conocimiento"))
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("El conocimiento no es JSON válido: ${e.message}", e)
        }

        // Lo aprendido en caliente: cada hecho es su propia intención.
        memoria.aprendido.forEachIndexed { i, hecho ->
            val id = "aprendido:$i"
            intenciones[id] = listOf(hecho.respuesta)
            entradas.add(Entrada(hecho.pregunta, id, "aprendido"))
        }

        vectorizador.entrenar(entradas.map { it.patron })
        for (entrada in entradas) entrada.vector = vectorizador.vectorizar(entrada.patron)
    }

    /** Prepara la tabla de capitales para buscar sin tildes ni mayúsculas. */
    private fun indexarPaises(paises: JSONObject?) {
        if (paises == null) {
            capitales = emptyMap()
            alias = emptyMap()
            return
        }
        val tabla = HashMap<String, Pair<String, String>>()
        paises.optJSONObject("capitales")?.let { capitalesJson ->
            for (pais in capitalesJson.keys()) {
                val mostrar = pais.split(" ").joinToString(" ") { parte ->
                    parte.replaceFirstChar { it.uppercase() }
                }
                tabla[Texto.normalizar(pais)] = mostrar to capitalesJson.getString(pais)
            }
        }
        val equivalencias = HashMap<String, String>()
        paises.optJSONObject("alias")?.let { aliasJson ->
            for (clave in aliasJson.keys()) {
                equivalencias[Texto.normalizar(clave)] = Texto.normalizar(aliasJson.getString(clave))
            }
        }
        capitales = tabla
        alias = equivalencias
    }

    /** La mejor coincidencia entre lo que el usuario le enseñó, y nada más. */
    private fun mejorAprendido(texto: String): Pair<String, Double>? {
        val aprendidas = entradas.filter { it.fuente == "aprendido" }
        if (aprendidas.isEmpty()) return null
        val consulta = vectorizador.vectorizar(texto, corregir = true)
        if (consulta.isEmpty()) return null
        val mejor = aprendidas.maxByOrNull { Vectorizador.similitud(consulta, it.vector) } ?: return null
        return mejor.intencion to Vectorizador.similitud(consulta, mejor.vector)
    }

    /** Devuelve las intenciones ordenadas de mejor a peor coincidencia. */
    fun clasificar(texto: String): List<Triple<String, Double, String>> {
        val consulta = vectorizador.vectorizar(texto, corregir = true)
        if (consulta.isEmpty()) return emptyList()

        val mejor = HashMap<String, Pair<Double, String>>()
        for (entrada in entradas) {
            val puntaje = Vectorizador.similitud(consulta, entrada.vector)
            val previo = mejor[entrada.intencion]
            if (previo == null || puntaje > previo.first) {
                mejor[entrada.intencion] = puntaje to entrada.fuente
            }
        }
        return mejor.entries
            .filter { it.value.first > 0 }
            .map { Triple(it.key, it.value.first, it.value.second) }
            .sortedByDescending { it.second }
    }

    /**
     * Los fragmentos de la base que más se parecen a la consulta.
     *
     * Es el contexto local que le pasamos al modelo generativo: lo que Rama
     * ya sabe, puesto delante para que no tenga que inventarlo.
     */
    fun recuperar(texto: String, maximo: Int = 3, umbral: Double = UMBRAL_PISTA): List<String> =
        clasificar(texto)
            .filter { it.second >= umbral }
            .take(maximo)
            .mapNotNull { intenciones[it.first]?.firstOrNull() }

    /** Rota entre las respuestas de una intención para no sonar a loop. */
    private fun elegirRespuesta(intencion: String): String {
        val opciones = intenciones[intencion].orEmpty()
        if (opciones.isEmpty()) return "..."
        if (opciones.size == 1) return opciones[0]
        val anterior = ultimaRespuesta[intencion]
        val indices = opciones.indices.filter { it != anterior }
        val elegido = indices[azar.nextInt(indices.size)]
        ultimaRespuesta[intencion] = elegido
        return opciones[elegido]
    }

    /** Punto de entrada único: texto del usuario -> respuesta de Rama. */
    fun responder(entrada: String): Respuesta {
        val texto = entrada.trim()
        if (texto.isEmpty()) {
            return Respuesta("Decime algo y te contesto.", "vacio", 0.0, "guardia")
        }

        memoria.registrarTurno("usuario", texto)
        val pasos = mutableListOf<Paso>()

        val normalizado = Texto.normalizar(texto)
        val tokens = Texto.tokenizar(texto)
        pasos.add(
            Paso(
                "Normalización",
                "«$texto»\n→ «$normalizado»\ntokens: ${tokens.joinToString(", ")}"
            )
        )

        val corregido = vectorizador.corrector.corregir(texto)
        pasos.add(
            Paso(
                "Corrección de erratas",
                if (corregido == normalizado) "sin cambios: todas las palabras me suenan conocidas"
                else "«$normalizado»\n→ «$corregido»  (Damerau-Levenshtein contra mi vocabulario)"
            )
        )

        for ((nombre, comando) in Skills.COMANDOS) {
            val salida = comando(texto, this)
            if (salida != null) {
                pasos.add(Paso("Comando", "reconocí una orden directa (aprender, olvidar o corregir)"))
                memoria.registrarTurno("rama", salida)
                return Respuesta(salida, nombre, 1.0, "comando", emptyList(), pasos)
            }
        }

        // Lo que te enseñó el usuario manda sobre cualquier habilidad: es la
        // forma que tiene de corregirla, y no serviría de nada si perdiera.
        val ensenado = mejorAprendido(texto)
        if (ensenado != null && ensenado.second >= UMBRAL_ALTO) {
            val respuesta = elegirRespuesta(ensenado.first)
            pasos.add(
                Paso(
                    "Memoria",
                    "esto me lo enseñaste vos (parecido ${"%.3f".format(ensenado.second)}), " +
                        "así que va antes que mis habilidades"
                )
            )
            memoria.ultimaPreguntaSinRespuesta = null
            memoria.registrarTurno("rama", respuesta)
            return Respuesta(respuesta, ensenado.first, redondear(ensenado.second), "aprendido", emptyList(), pasos)
        }

        for ((nombre, skill) in Skills.TODAS) {
            val salida = skill(texto, this)
            if (salida != null) {
                pasos.add(Paso("Habilidades", "coincidió la habilidad «$nombre»: respuesta exacta, sin buscar por similitud"))
                pasos.add(Paso("Decisión", "una habilidad determinista resuelve la consulta, así que no hay incertidumbre"))
                memoria.registrarTurno("rama", salida)
                return Respuesta(salida, nombre, 1.0, "skill", emptyList(), pasos)
            }
        }
        pasos.add(
            Paso(
                "Habilidades",
                "ninguna de las ${Skills.TODAS.size} habilidades aplicó " +
                    "(${Skills.TODAS.joinToString(", ") { it.first }})"
            )
        )

        val vector = vectorizador.vectorizar(texto, corregir = true)
        val palabras = vector.keys.count { it.startsWith("p:") }
        val bigramas = vector.keys.count { it.startsWith("b:") }
        val trigramas = vector.keys.count { it.startsWith("n:") }
        pasos.add(
            Paso(
                "Vectorización TF-IDF",
                "${vector.size} rasgos: $palabras raíces, $bigramas bigramas, $trigramas trigramas de letra\n" +
                    "comparo contra $totalPatrones patrones (vocabulario de $tamanioVocabulario rasgos)"
            )
        )

        val ranking = clasificar(texto)
        val candidatos = ranking.take(3).map { Candidato(it.first, redondear(it.second)) }
        pasos.add(
            Paso(
                "Similitud coseno",
                if (ranking.isEmpty()) "ningún patrón comparte rasgos con tu frase"
                else ranking.take(4).joinToString("\n") {
                    "${"%.3f".format(it.second)}  ${it.first.replace("_", " ")}"
                }
            )
        )

        val mejor = ranking.firstOrNull()
        if (mejor != null && mejor.second >= UMBRAL_BAJO) {
            var respuesta = elegirRespuesta(mejor.first)
            val segura = mejor.second >= UMBRAL_ALTO
            if (!segura) respuesta = "No estoy del todo segura, pero creo que va por acá: $respuesta"
            pasos.add(
                Paso(
                    "Decisión",
                    if (segura) "confianza ${"%.3f".format(mejor.second)} ≥ $UMBRAL_ALTO → respondo directo"
                    else "confianza ${"%.3f".format(mejor.second)} entre $UMBRAL_BAJO y $UMBRAL_ALTO → " +
                        "respondo, pero aviso que dudo"
                )
            )
            memoria.ultimaPreguntaSinRespuesta = null
            memoria.registrarTurno("rama", respuesta)
            return Respuesta(respuesta, mejor.first, redondear(mejor.second), mejor.third, candidatos, pasos)
        }

        val confianza = mejor?.second ?: 0.0
        pasos.add(
            Paso(
                "Decisión",
                "confianza ${"%.3f".format(confianza)} < $UMBRAL_BAJO → " +
                    "prefiero decir que no sé antes que inventar"
            )
        )
        val salida = sinRespuesta(texto, ranking)
        memoria.registrarTurno("rama", salida)
        return Respuesta(salida, "desconocida", redondear(confianza), "fallback", candidatos, pasos)
    }

    /** Admite la ignorancia, pero deja al usuario en algún lado. */
    private fun sinRespuesta(texto: String, ranking: List<Triple<String, Double, String>>): String {
        memoria.ultimaPreguntaSinRespuesta = texto
        val base = SIN_IDEA[azar.nextInt(SIN_IDEA.size)]
        val cercanos = ranking.take(2)
            .filter { it.second >= UMBRAL_SUGERENCIA && !it.first.startsWith("aprendido:") }
            .joinToString(", ") { it.first.replace("_", " ") }
        if (cercanos.isNotEmpty()) {
            return "$base De lo parecido que sí manejo: $cercanos. " +
                "Si no era por ahí, enseñame la respuesta con «responde: ...» y queda guardada."
        }
        return "$base Enseñame escribiendo «responde: la respuesta que esperabas» y no vuelvo a fallar " +
            "en esto. También sé de tecnología, ciencia, capitales, cuentas, conversiones y fechas: " +
            "probá preguntarme por ahí."
    }

    companion object {
        // Bandas de confianza. Debajo de la baja, Rama admite que no sabe.
        const val UMBRAL_ALTO = 0.42
        const val UMBRAL_BAJO = 0.20
        const val UMBRAL_PISTA = 0.09
        const val UMBRAL_SUGERENCIA = 0.16

        private val SIN_IDEA = arrayOf(
            "No sé responder eso todavía.",
            "Eso se me escapa.",
            "No tengo nada parecido en mi base.",
        )

        fun redondear(valor: Double, decimales: Int = 4): Double {
            var factor = 1.0
            repeat(decimales) { factor *= 10 }
            return round(valor * factor) / factor
        }
    }
}
