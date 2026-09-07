package ar.rama.ai.motor

import kotlin.math.abs
import kotlin.math.min

/**
 * Corrector ortográfico mínimo contra el vocabulario que Rama conoce.
 *
 * No es un diccionario del español: sólo arregla palabras que *casi* coinciden
 * con algo que Rama ya vio, para que una errata no tire abajo la similitud.
 */
class Corrector {

    private var vocabulario: Set<String> = emptySet()
    private val cache = HashMap<String, String>()

    fun entrenar(documentos: List<String>): Corrector {
        vocabulario = documentos
            .flatMap { Texto.normalizar(it).split(" ") }
            .filter { it.length >= LARGO_MINIMO }
            .toSet()
        cache.clear()
        return this
    }

    fun corregirPalabra(palabra: String): String {
        if (palabra.length < LARGO_MINIMO || palabra in vocabulario) return palabra
        cache[palabra]?.let { return it }

        val tope = if (palabra.length < 7) 1 else 2
        var mejor = palabra
        var mejorDistancia = tope + 1
        for (candidata in vocabulario) {
            val distancia = distanciaEdicion(palabra, candidata, tope)
            if (distancia < mejorDistancia) {
                mejor = candidata
                mejorDistancia = distancia
                if (distancia == 1) break
            }
        }
        cache[palabra] = mejor
        return mejor
    }

    fun corregir(texto: String): String {
        if (vocabulario.isEmpty()) return texto
        return Texto.normalizar(texto).split(" ").joinToString(" ") { corregirPalabra(it) }
    }

    companion object {
        const val LARGO_MINIMO = 4

        /**
         * Damerau-Levenshtein (con transposición) y corte temprano.
         *
         * Contar la transposición como una sola edición importa: "pyhton" es el
         * error de tipeo más común y queda a distancia 1 de "python".
         * Devuelve `tope + 1` en cuanto se sabe que la distancia lo supera.
         */
        fun distanciaEdicion(a: String, b: String, tope: Int = 2): Int {
            if (abs(a.length - b.length) > tope) return tope + 1
            if (a == b) return 0

            var anterior = IntArray(b.length + 1)
            var previa = IntArray(b.length + 1) { it }
            var actual = IntArray(b.length + 1)

            for (i in 1..a.length) {
                actual[0] = i
                var minimoFila = actual[0]
                for (j in 1..b.length) {
                    var costo = min(
                        min(previa[j] + 1, actual[j - 1] + 1),
                        previa[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        costo = min(costo, anterior[j - 2] + 1)
                    }
                    actual[j] = costo
                    if (costo < minimoFila) minimoFila = costo
                }
                if (minimoFila > tope) return tope + 1
                // Rotamos las tres filas sin reservar memoria nueva.
                val reciclada = anterior
                anterior = previa
                previa = actual
                actual = reciclada
            }
            return previa[b.length]
        }
    }
}
