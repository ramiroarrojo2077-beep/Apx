package ar.rama.ai.motor

/** Qué clase de énfasis lleva un tramo de texto. */
enum class Enfasis { NEGRITA, CURSIVA, CODIGO, TITULO }

/** Un tramo con formato, en posiciones del texto ya limpio. */
data class Marca(val enfasis: Enfasis, val desde: Int, val hasta: Int)

/** Texto sin los símbolos de markdown, más las marcas para pintarlo. */
data class TextoFormateado(val texto: String, val marcas: List<Marca>)

/**
 * Interpreta el markdown liviano que escriben los modelos.
 *
 * Todos contestan con asteriscos, guiones y almohadillas: sin esto, el chat
 * muestra los símbolos crudos y se lee peor que un mensaje sin formato. Se
 * cubre lo que aparece de verdad, no el estándar completo.
 */
object Formato {

    private val ENCABEZADO = Regex("^\\s{0,3}#{1,6}\\s+")
    private val VINETA = Regex("^(\\s*)[-*+]\\s+")

    fun analizar(markdown: String): TextoFormateado {
        val salida = StringBuilder()
        val marcas = mutableListOf<Marca>()
        val lineas = markdown.split("\n")

        for ((i, cruda) in lineas.withIndex()) {
            var linea = cruda
            val encabezado = ENCABEZADO.find(linea)
            val esTitulo = encabezado != null
            linea = if (esTitulo) {
                linea.substring(encabezado!!.value.length)
            } else {
                VINETA.replace(linea) { coincidencia -> coincidencia.groupValues[1] + "•  " }
            }

            val inicio = salida.length
            enLinea(linea, salida, marcas)
            if (esTitulo && salida.length > inicio) {
                marcas.add(Marca(Enfasis.TITULO, inicio, salida.length))
            }
            if (i < lineas.size - 1) salida.append('\n')
        }
        return TextoFormateado(salida.toString(), marcas)
    }

    private fun enLinea(linea: String, salida: StringBuilder, marcas: MutableList<Marca>) {
        var i = 0
        while (i < linea.length) {
            val resto = linea.substring(i)
            // El orden importa: ** antes que *, o la negrita se lee como cursiva.
            val encontrado = tramo(resto, "**", Enfasis.NEGRITA)
                ?: tramo(resto, "__", Enfasis.NEGRITA)
                ?: tramo(resto, "`", Enfasis.CODIGO)
                ?: tramo(resto, "*", Enfasis.CURSIVA)

            if (encontrado == null) {
                salida.append(linea[i])
                i++
                continue
            }
            val desde = salida.length
            salida.append(encontrado.contenido)
            marcas.add(Marca(encontrado.enfasis, desde, salida.length))
            i += encontrado.consumido
        }
    }

    private class Tramo(val contenido: String, val consumido: Int, val enfasis: Enfasis)

    /** Busca «marcaTEXTOmarca» al principio del resto de la línea. */
    private fun tramo(resto: String, marca: String, enfasis: Enfasis): Tramo? {
        if (!resto.startsWith(marca)) return null
        val cierre = resto.indexOf(marca, marca.length)
        if (cierre <= marca.length) return null  // vacío o sin cerrar
        val contenido = resto.substring(marca.length, cierre)
        if (contenido.isBlank()) return null
        return Tramo(contenido, cierre + marca.length, enfasis)
    }
}
