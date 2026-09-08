package ar.rama.ai.motor

/**
 * Saca del texto visible el razonamiento interno del modelo.
 *
 * Los modelos tipo Qwen3 escriben lo que están pensando entre `<think>` y
 * `</think>` antes de contestar. Eso no va en el chat: se guarda aparte y se
 * puede ver en el modo pensar, que es donde tiene sentido.
 *
 * Funciona sobre el texto que llega de a pedacitos, así que tiene que
 * aguantar que una etiqueta venga partida entre dos fragmentos.
 */
class FiltroPensamiento {

    private val pendiente = StringBuilder()
    private val razonamiento = StringBuilder()
    private var dentro = false

    /** Lo que el modelo pensó, ya separado de la respuesta. */
    val pensado: String get() = razonamiento.toString().trim()

    /** Recibe un fragmento y devuelve sólo la parte que se puede mostrar. */
    fun procesar(fragmento: String): String {
        pendiente.append(fragmento)
        val visible = StringBuilder()

        while (true) {
            val etiquetas = if (dentro) CIERRES else APERTURAS
            val encontrada = primeraEtiqueta(pendiente, etiquetas)

            if (encontrada != null) {
                val (posicion, etiqueta) = encontrada
                val previo = pendiente.substring(0, posicion)
                if (dentro) razonamiento.append(previo) else visible.append(previo)
                pendiente.delete(0, posicion + etiqueta.length)
                dentro = !dentro
                continue
            }

            // Sin etiqueta completa: soltamos todo menos la cola que todavía
            // podría ser el principio de una.
            val seguro = pendiente.length - largoDeEtiquetaAMedias(pendiente)
            if (seguro > 0) {
                val texto = pendiente.substring(0, seguro)
                if (dentro) razonamiento.append(texto) else visible.append(texto)
                pendiente.delete(0, seguro)
            }
            break
        }
        return visible.toString()
    }

    /** Al terminar, lo que quedó guardado ya no puede ser una etiqueta. */
    fun cerrar(): String {
        val resto = pendiente.toString()
        pendiente.setLength(0)
        if (dentro) {
            razonamiento.append(resto)
            return ""
        }
        return resto
    }

    companion object {
        private val APERTURAS = listOf("<think>", "<thinking>", "<reasoning>", "<thought>")
        private val CIERRES = listOf("</think>", "</thinking>", "</reasoning>", "</thought>")
        private val LARGO_MAXIMO = (APERTURAS + CIERRES).maxOf { it.length }

        private fun primeraEtiqueta(texto: CharSequence, etiquetas: List<String>): Pair<Int, String>? {
            var mejor: Pair<Int, String>? = null
            for (etiqueta in etiquetas) {
                val posicion = texto.indexOf(etiqueta)
                if (posicion >= 0 && (mejor == null || posicion < mejor.first)) {
                    mejor = posicion to etiqueta
                }
            }
            return mejor
        }

        /**
         * Cuántos caracteres del final podrían ser el comienzo de una etiqueta.
         * Ésos hay que retenerlos hasta ver qué viene después.
         */
        fun largoDeEtiquetaAMedias(texto: CharSequence): Int {
            val desde = maxOf(0, texto.length - LARGO_MAXIMO + 1)
            for (i in desde until texto.length) {
                if (texto[i] != '<') continue
                val cola = texto.substring(i)
                if ((APERTURAS + CIERRES).any { it.startsWith(cola) }) return texto.length - i
            }
            return 0
        }

        private fun CharSequence.indexOf(aguja: String): Int {
            outer@ for (i in 0..length - aguja.length) {
                for (j in aguja.indices) if (this[i + j] != aguja[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
