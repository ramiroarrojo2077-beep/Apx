package ar.rama.ai.motor

/** Qué clase de énfasis lleva un tramo de texto. */
enum class Enfasis {
    NEGRITA,
    CURSIVA,
    /** Código dentro de una frase, entre acentos graves. */
    CODIGO,
    TITULO,
    /** Un bloque entero de código, el que va entre ``` y ```. */
    BLOQUE,
}

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
    private val CERCA = Regex("^\\s{0,3}(```|~~~)")

    fun analizar(markdown: String): TextoFormateado {
        val salida = StringBuilder()
        val marcas = mutableListOf<Marca>()
        val lineas = markdown.split("\n")

        // Dentro de un bloque de código no se interpreta nada: ahí un asterisco
        // es una multiplicación, un guion al principio es un menos y una
        // almohadilla es un comentario. Tratarlos como markdown rompía el
        // código justo cuando más importa que salga tal cual se escribió.
        var enBloque = false
        var desdeBloque = 0

        for ((i, cruda) in lineas.withIndex()) {
            if (CERCA.containsMatchIn(cruda)) {
                if (enBloque) {
                    marcas.add(Marca(Enfasis.BLOQUE, desdeBloque, recortarFinal(salida, desdeBloque)))
                    enBloque = false
                } else {
                    enBloque = true
                    desdeBloque = salida.length
                }
                // La línea de las comillas no se muestra, y tampoco su salto:
                // si no, cada bloque deja un renglón vacío arriba y abajo.
                continue
            }
            if (enBloque) {
                salida.append(cruda)
                if (i < lineas.size - 1) salida.append('\n')
                continue
            }
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
        // Un modelo se queda sin tokens en mitad del código más seguido de lo
        // que uno quisiera. Lo que quedó abierto se muestra igual como bloque:
        // es código a medias, pero se lee como código.
        if (enBloque) marcas.add(Marca(Enfasis.BLOQUE, desdeBloque, recortarFinal(salida, desdeBloque)))
        return TextoFormateado(salida.toString(), marcas)
    }

    /** El final del bloque, sin el salto de línea que lo cierra. */
    private fun recortarFinal(salida: StringBuilder, desde: Int): Int {
        var fin = salida.length
        while (fin > desde && salida[fin - 1] == '\n') fin--
        return fin
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
