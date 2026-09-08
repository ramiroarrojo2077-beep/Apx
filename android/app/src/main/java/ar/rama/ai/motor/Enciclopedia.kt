package ar.rama.ai.motor

import org.json.JSONObject

/**
 * Miles de temas con búsqueda directa.
 *
 * A diferencia de la base conversacional, que compara por similitud contra
 * cada patrón, acá se extrae el sujeto de la pregunta y se busca en una tabla.
 * Es O(1): entran decenas de miles de temas sin costo de arranque ni memoria,
 * que es justo lo que el TF-IDF no aguanta a esta escala.
 */
class Enciclopedia(json: String) {

    private val entradas = HashMap<String, String>()

    /**
     * Índice por palabra: permite encontrar "albert einstein" preguntando
     * sólo "einstein", que es como pregunta la gente.
     */
    private val porPalabra = HashMap<String, MutableList<String>>()

    val cantidad: Int get() = entradas.size

    init {
        try {
            val raiz = JSONObject(json).optJSONObject("entradas")
            if (raiz != null) {
                for (clave in raiz.keys()) {
                    val texto = raiz.optString(clave)
                    if (texto.isNotEmpty()) entradas[Texto.normalizar(clave)] = texto
                }
            }
        } catch (e: Exception) {
            entradas.clear()  // una enciclopedia rota no debe impedir arrancar
        }

        for (clave in entradas.keys) {
            for (palabra in clave.split(" ")) {
                if (palabra.length >= 4) {
                    porPalabra.getOrPut(palabra) { mutableListOf() }.add(clave)
                }
            }
        }
    }

    /** Devuelve la explicación del tema, o null si no lo tiene. */
    fun buscar(pregunta: String): String? {
        val sujeto = sujetoDe(pregunta) ?: return null
        return porClave(sujeto)
    }

    private fun porClave(sujeto: String): String? {
        entradas[sujeto]?.let { return it }

        // "los planetas" -> "planeta"; "células" -> "célula"
        val singular = when {
            sujeto.endsWith("ces") -> sujeto.dropLast(3) + "z"
            sujeto.endsWith("es") && sujeto.length > 4 -> sujeto.dropLast(2)
            sujeto.endsWith("s") && sujeto.length > 3 -> sujeto.dropLast(1)
            else -> null
        }
        if (singular != null) entradas[singular]?.let { return it }

        // Y al revés, por si la entrada está en plural.
        entradas["${sujeto}s"]?.let { return it }
        entradas["${sujeto}es"]?.let { return it }

        return porPalabras(sujeto)
    }

    /** Busca la entrada que contenga todas las palabras del sujeto. */
    private fun porPalabras(sujeto: String): String? {
        val palabras = sujeto.split(" ").filter { it.length >= 4 }
        if (palabras.isEmpty()) return null

        var candidatas = porPalabra[palabras[0]]?.toMutableSet() ?: return null
        for (palabra in palabras.drop(1)) {
            val otras = porPalabra[palabra] ?: return null
            candidatas.retainAll(otras.toSet())
            if (candidatas.isEmpty()) return null
        }
        // La más corta es la más específica.
        val elegida = candidatas.minByOrNull { it.length } ?: return null
        return entradas[elegida]
    }

    companion object {
        /** Fórmulas con las que la gente pregunta por algo. */
        private val PREGUNTAS = listOf(
            Regex("^(?:me podes decir |decime |sabes )?(?:que|qué) (?:es|son|significa[n]?|quiere decir) (?:el |la |los |las |un |una |unos |unas )?(.+)$"),
            Regex("^(?:quien|quién) (?:es|fue|era|son|fueron) (?:el |la |los |las )?(.+)$"),
            Regex("^(?:cuando|cuándo) (?:fue|paso|pasó|ocurrio|ocurrió|sucedio|sucedió|se invento|se inventó|nacio|nació|murio|murió) (?:el |la |los |las )?(.+)$"),
            Regex("^(?:donde|dónde) (?:esta|está|queda|se encuentra|nacio|nació) (?:el |la |los |las )?(.+)$"),
            Regex("^para (?:que|qué) (?:sirve|sirven|se usa|se usan) (?:el |la |los |las )?(.+)$"),
            Regex("^(?:como|cómo) (?:funciona|funcionan|se hace|se forma|se produce) (?:el |la |los |las )?(.+)$"),
            Regex("^(?:hablame|contame|explicame|explícame|contame algo) (?:de|sobre|del|acerca de) (?:el |la |los |las )?(.+)$"),
            Regex("^(?:definicion|definición|significado|concepto) de (?:el |la |los |las )?(.+)$"),
            Regex("^(?:informacion|información|datos) (?:de|sobre) (?:el |la |los |las )?(.+)$"),
            Regex("^(?:en que|en qué) (?:consiste|continente esta|continente está|pais esta|país está) (?:el |la |los |las )?(.+)$"),
        )
        private val BORDES = Regex("^[\\s¿¡]+|[\\s?!.]+$")

        /**
         * Saca el sujeto de la pregunta.
         *
         * "¿Qué es la fotosíntesis?" -> "fotosintesis". Si la frase no tiene
         * forma de pregunta pero es corta, se toma entera: mucha gente escribe
         * sólo el tema.
         */
        fun sujetoDe(pregunta: String): String? {
            val limpia = Texto.normalizar(BORDES.replace(pregunta, ""))
            if (limpia.isEmpty()) return null

            for (forma in PREGUNTAS) {
                val encontrado = forma.find(limpia)
                if (encontrado != null) {
                    val sujeto = encontrado.groupValues[1].trim()
                    if (sujeto.isNotEmpty()) return sujeto
                }
            }
            // Un tema escrito solo: "fotosíntesis", "revolución francesa".
            return if (limpia.split(" ").size <= 4) limpia else null
        }
    }
}
