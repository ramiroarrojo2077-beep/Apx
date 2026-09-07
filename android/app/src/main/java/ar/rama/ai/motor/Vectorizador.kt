package ar.rama.ai.motor

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Vectorizador TF-IDF con similitud coseno, escrito a mano.
 *
 * Cada documento se representa como un vector disperso que mezcla raíces de
 * palabra (el significado), bigramas de palabra (el orden) y trigramas de
 * letra (la red de seguridad ante erratas).
 */
class Vectorizador {

    private var idf: Map<String, Double> = emptyMap()
    private var idfDefecto: Double = 1.0
    val corrector = Corrector()

    var nDocumentos: Int = 0
        private set

    val tamanioVocabulario: Int get() = idf.size

    fun entrenar(documentos: List<String>): Vectorizador {
        nDocumentos = documentos.size
        corrector.entrenar(documentos)

        val frecuenciaDoc = HashMap<String, Int>()
        for (doc in documentos) {
            for (rasgo in rasgos(doc).keys) {
                frecuenciaDoc[rasgo] = (frecuenciaDoc[rasgo] ?: 0) + 1
            }
        }
        val total = maxOf(nDocumentos, 1).toDouble()
        idf = frecuenciaDoc.mapValues { (_, df) -> ln((total + 1) / (df + 1)) + 1.0 }
        // Un rasgo nunca visto es, por definición, muy informativo.
        idfDefecto = ln(total + 1) + 1.0
        return this
    }

    /**
     * Devuelve el vector TF-IDF normalizado (norma L2 = 1).
     *
     * Con [corregir] las erratas se acercan al vocabulario conocido; se usa
     * sólo para la consulta, nunca para indexar el corpus.
     */
    fun vectorizar(texto: String, corregir: Boolean = false): Map<String, Double> {
        val entrada = if (corregir) corrector.corregir(texto) else texto
        val bolsa = rasgos(entrada)
        if (bolsa.isEmpty()) return emptyMap()

        val vector = HashMap<String, Double>(bolsa.size * 2)
        for ((rasgo, tf) in bolsa) {
            vector[rasgo] = (1.0 + ln(tf.toDouble())) * (idf[rasgo] ?: idfDefecto) * pesoFamilia(rasgo)
        }
        val norma = sqrt(vector.values.sumOf { it * it })
        if (norma == 0.0) return emptyMap()
        for (rasgo in vector.keys.toList()) {
            vector[rasgo] = vector.getValue(rasgo) / norma
        }
        return vector
    }

    companion object {
        const val PESO_PALABRA = 1.0
        const val PESO_NGRAMA = 0.5
        const val N_GRAMA = 3

        private fun ngramas(texto: String, n: Int = N_GRAMA): List<String> {
            val nucleo = Texto.normalizar(texto)
            if (nucleo.isEmpty()) return emptyList()
            val plano = " $nucleo "
            if (plano.length <= n) return listOf(plano)
            return (0..plano.length - n).map { plano.substring(it, it + n) }
        }

        /** Cuenta los rasgos crudos (sin IDF ni pesos) de un texto. */
        fun rasgos(texto: String): Map<String, Int> {
            val bolsa = HashMap<String, Int>()
            fun sumar(rasgo: String) { bolsa[rasgo] = (bolsa[rasgo] ?: 0) + 1 }

            val palabras = Texto.tokenizar(texto)
            for (palabra in palabras) sumar("p:$palabra")
            // Bigramas de palabra: distinguen "no puedo" de "puedo".
            for (i in 0 until palabras.size - 1) sumar("b:${palabras[i]}_${palabras[i + 1]}")
            for (gram in ngramas(texto)) sumar("n:$gram")
            return bolsa
        }

        /** Los n-gramas de letra pesan menos: son la red de seguridad, no la señal. */
        private fun pesoFamilia(rasgo: String): Double =
            if (rasgo.startsWith("n:")) PESO_NGRAMA else PESO_PALABRA

        /** Coseno entre dos vectores ya normalizados: producto punto directo. */
        fun similitud(a: Map<String, Double>, b: Map<String, Double>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val chico = if (a.size > b.size) b else a
            val grande = if (a.size > b.size) a else b
            var total = 0.0
            for ((rasgo, peso) in chico) {
                val otro = grande[rasgo]
                if (otro != null) total += peso * otro
            }
            return total
        }
    }
}
