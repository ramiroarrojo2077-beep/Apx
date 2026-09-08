package ar.rama.ai.motor

import java.time.LocalDate
import java.util.Locale

/**
 * Habilidades que calculan la respuesta a partir de datos, en vez de recitar
 * una respuesta escrita: geografía, conversión de unidades, calendario y
 * operaciones sobre texto.
 *
 * Son las que le suben el techo a Rama: cubren clases enteras de preguntas
 * en lugar de casos sueltos.
 */
object SkillsDatos {

    // --------------------------------------------------------- geografía

    private val CAPITAL_DE = Regex("(?:cual es la |cual es |decime la |dame la )?capital (?:de|del) (?:la |el |los )?(.+)")
    private val ES_CAPITAL = Regex("(?:de que pais es (?:la )?capital|de donde es capital) (.+)")
    private val PAIS_DE_CAPITAL = Regex("(.+?) es (?:la )?capital de que pais")
    private val RUIDO_PAIS = Regex("^(pais |republica de |la |el )")

    private fun buscarPais(nombre: String, rama: Rama): Pair<String, String>? {
        var clave = Texto.normalizar(nombre).trim(' ', '?', '.', '!')
        clave = RUIDO_PAIS.replace(clave, "").trim()
        clave = rama.alias[clave] ?: clave
        rama.capitales[clave]?.let { return it }
        // "capital de francia hoy" y variantes con palabras de más.
        for ((candidato, datos) in rama.capitales) {
            if (clave.split(" ").contains(candidato) || clave.startsWith("$candidato ")) return datos
        }
        return null
    }

    val geografia: (String, Rama) -> String? = { texto, rama ->
        val plano = Texto.normalizar(texto)
        val capitalDe = CAPITAL_DE.find(plano)
        if (capitalDe != null) {
            val encontrado = buscarPais(capitalDe.groupValues[1], rama)
            if (encontrado != null) "La capital de ${encontrado.first} es ${encontrado.second}." else null
        } else {
            val inversa = ES_CAPITAL.find(plano) ?: PAIS_DE_CAPITAL.find(plano)
            if (inversa == null) null else {
                val buscada = Texto.normalizar(inversa.groupValues[1]).trim(' ', '?', '.', '!')
                rama.capitales.values.firstOrNull { (_, capital) ->
                    val normal = Texto.normalizar(capital)
                    normal.startsWith(buscada) || normal.split(" ").contains(buscada)
                }?.let { "${it.second} es la capital de ${it.first}." }
            }
        }
    }

    // ------------------------------------------------------ enciclopedia

    /**
     * Miles de temas resueltos por búsqueda directa.
     *
     * Va después de las habilidades que calculan: si preguntás "cuánto es
     * 2+2" queremos la cuenta, no la definición de la suma.
     */
    val enciclopedia: (String, Rama) -> String? = { texto, rama ->
        rama.enciclopedia?.buscar(texto)
    }

    // ------------------------------------------- conversión de unidades

    /** Cada unidad se define por su equivalencia en la unidad base de su familia. */
    private val FAMILIAS: Map<String, Map<String, Double>> = mapOf(
        "longitud" to mapOf(
            "km" to 1000.0, "kilometro" to 1000.0, "kilometros" to 1000.0,
            "m" to 1.0, "metro" to 1.0, "metros" to 1.0,
            "cm" to 0.01, "centimetro" to 0.01, "centimetros" to 0.01,
            "mm" to 0.001, "milimetro" to 0.001, "milimetros" to 0.001,
            "milla" to 1609.344, "millas" to 1609.344, "mi" to 1609.344,
            "pie" to 0.3048, "pies" to 0.3048, "ft" to 0.3048,
            "pulgada" to 0.0254, "pulgadas" to 0.0254, "in" to 0.0254,
            "yarda" to 0.9144, "yardas" to 0.9144,
        ),
        "masa" to mapOf(
            "kg" to 1.0, "kilo" to 1.0, "kilos" to 1.0, "kilogramo" to 1.0, "kilogramos" to 1.0,
            "g" to 0.001, "gramo" to 0.001, "gramos" to 0.001,
            "mg" to 0.000001, "miligramo" to 0.000001, "miligramos" to 0.000001,
            "tonelada" to 1000.0, "toneladas" to 1000.0,
            "libra" to 0.45359237, "libras" to 0.45359237, "lb" to 0.45359237,
            "onza" to 0.028349523, "onzas" to 0.028349523, "oz" to 0.028349523,
        ),
        "volumen" to mapOf(
            "l" to 1.0, "litro" to 1.0, "litros" to 1.0,
            "ml" to 0.001, "mililitro" to 0.001, "mililitros" to 0.001,
            "galon" to 3.785411784, "galones" to 3.785411784,
            "taza" to 0.24, "tazas" to 0.24,
        ),
        "velocidad" to mapOf(
            "kmh" to 1.0, "km h" to 1.0, "kilometros por hora" to 1.0,
            "mph" to 1.609344, "millas por hora" to 1.609344,
            "nudo" to 1.852, "nudos" to 1.852,
            "ms" to 3.6, "metros por segundo" to 3.6,
        ),
    )

    private val TEMPERATURAS = setOf("celsius", "centigrados", "c", "fahrenheit", "f", "kelvin", "k")

    private val CONVERSION = Regex(
        "(?:cuant[oa]s?\\s+([a-z/ ]+?)\\s+(?:son|es|hay en|equivalen? a)\\s*)?" +
            "(\\d+(?:[.,]\\d+)?)\\s*(?:grados\\s+)?([a-z/]+)\\s*(?:a|en|son|equivalen a)\\s+(?:grados\\s+)?([a-z/ ]+)"
    )
    private val CUANTOS_SON = Regex(
        "cuant[oa]s?\\s+(?:grados\\s+)?([a-z/ ]+?)\\s+(?:son|es|equivalen? a)\\s+" +
            "(\\d+(?:[.,]\\d+)?)\\s*(?:grados\\s+)?([a-z/]+)"
    )

    private fun familiaDe(unidad: String): Map<String, Double>? =
        FAMILIAS.values.firstOrNull { unidad in it }

    private fun aCelsius(valor: Double, desde: String): Double? = when (desde) {
        "celsius", "centigrados", "c" -> valor
        "fahrenheit", "f" -> (valor - 32) * 5 / 9
        "kelvin", "k" -> valor - 273.15
        else -> null
    }

    private fun desdeCelsius(celsius: Double, hacia: String): Double? = when (hacia) {
        "celsius", "centigrados", "c" -> celsius
        "fahrenheit", "f" -> celsius * 9 / 5 + 32
        "kelvin", "k" -> celsius + 273.15
        else -> null
    }

    val conversiones: (String, Rama) -> String? = { texto, _ ->
        val plano = Texto.normalizar(texto).replace(",", ".")
        val invertida = CUANTOS_SON.find(plano)
        val directa = if (invertida == null) CONVERSION.find(plano) else null

        val datos: Triple<Double, String, String>? = when {
            invertida != null -> Triple(
                invertida.groupValues[2].toDouble(),
                invertida.groupValues[3].trim(),
                invertida.groupValues[1].trim(),
            )
            directa != null -> Triple(
                directa.groupValues[2].toDouble(),
                directa.groupValues[3].trim(),
                directa.groupValues[4].trim(),
            )
            else -> null
        }

        if (datos == null) null else {
            val (valor, desde, hacia) = datos
            if (desde in TEMPERATURAS && hacia in TEMPERATURAS) {
                val celsius = aCelsius(valor, desde)
                val resultado = if (celsius == null) null else desdeCelsius(celsius, hacia)
                if (resultado == null) null
                else "${Calculadora.formatear(valor)}° $desde son " +
                    "${Calculadora.formatear(Math.round(resultado * 100) / 100.0)}° $hacia."
            } else {
                val familia = familiaDe(desde)
                if (familia == null || familiaDe(hacia) !== familia) null
                else {
                    val resultado = valor * familia.getValue(desde) / familia.getValue(hacia)
                    "${Calculadora.formatear(valor)} $desde son " +
                        "${Calculadora.formatear(Math.round(resultado * 1e6) / 1e6)} $hacia."
                }
            }
        }
    }

    // ------------------------------------------------ fechas y calendario

    private val FECHA_TEXTO = Regex("(\\d{1,2})\\s+de\\s+([a-z]+)(?:\\s+del?\\s+(\\d{4}))?")
    private val FECHA_BARRAS = Regex("(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?")
    private val QUE_DIA_CAE = Regex("que dia (?:cae|es|fue|sera)\\b")
    private val CUANTO_FALTA = Regex("cuantos? (?:dias|falta|faltan)\\b|cuanto falta\\b")
    private val EDAD = Regex("cuantos años tengo|que edad tengo|mi edad")
    private val ANIO = Regex("\\b(19\\d{2}|20\\d{2})\\b")

    private fun interpretarFecha(plano: String, anioDefecto: Int): LocalDate? {
        FECHA_TEXTO.find(plano)?.let { m ->
            val nombreMes = m.groupValues[2]
            val mes = Skills.MESES.indexOfFirst {
                Texto.quitarAcentos(it) == nombreMes ||
                    Texto.quitarAcentos(it).startsWith(nombreMes.take(4))
            }
            if (mes >= 0) {
                val anio = m.groupValues[3].toIntOrNull() ?: anioDefecto
                return runCatching { LocalDate.of(anio, mes + 1, m.groupValues[1].toInt()) }.getOrNull()
            }
        }
        FECHA_BARRAS.find(plano)?.let { m ->
            val crudo = m.groupValues[3]
            val anio = when {
                crudo.length == 4 -> crudo.toInt()
                crudo.isNotEmpty() -> 2000 + crudo.toInt()
                else -> anioDefecto
            }
            return runCatching {
                LocalDate.of(anio, m.groupValues[2].toInt(), m.groupValues[1].toInt())
            }.getOrNull()
        }
        return null
    }

    private fun enPalabras(fecha: LocalDate) =
        "${fecha.dayOfMonth} de ${Skills.MESES[fecha.monthValue - 1]} de ${fecha.year}"

    private fun diaSemana(fecha: LocalDate) = Skills.DIAS[fecha.dayOfWeek.value - 1]

    val calendario: (String, Rama) -> String? = { texto, _ ->
        val plano = Texto.normalizar(texto)
        val hoy = LocalDate.now()

        when {
            EDAD.containsMatchIn(plano) -> {
                val nacimiento = interpretarFecha(plano, hoy.year)
                val anio = ANIO.find(plano)
                when {
                    nacimiento != null -> {
                        var edad = hoy.year - nacimiento.year
                        if (hoy.monthValue < nacimiento.monthValue ||
                            (hoy.monthValue == nacimiento.monthValue && hoy.dayOfMonth < nacimiento.dayOfMonth)
                        ) edad--
                        "Tenés $edad años."
                    }
                    anio != null -> {
                        val n = anio.groupValues[1].toInt()
                        "Si naciste en $n, este año cumplís ${hoy.year - n}."
                    }
                    else -> null
                }
            }

            CUANTO_FALTA.containsMatchIn(plano) -> {
                var objetivo = interpretarFecha(plano, hoy.year)
                if (objetivo == null && plano.contains("navidad")) objetivo = LocalDate.of(hoy.year, 12, 25)
                if (objetivo == null && (plano.contains("año nuevo") || plano.contains("fin de año"))) {
                    objetivo = LocalDate.of(hoy.year, 12, 31)
                }
                if (objetivo == null) null else {
                    if (objetivo.isBefore(hoy)) objetivo = objetivo.plusYears(1)
                    val faltan = java.time.temporal.ChronoUnit.DAYS.between(hoy, objetivo)
                    if (faltan == 0L) "Es hoy mismo: ${enPalabras(objetivo)}."
                    else "Faltan $faltan días para el ${enPalabras(objetivo)} (${diaSemana(objetivo)})."
                }
            }

            QUE_DIA_CAE.containsMatchIn(plano) -> {
                val objetivo = interpretarFecha(plano, hoy.year)
                if (objetivo == null) null
                else "El ${enPalabras(objetivo)} cae ${diaSemana(objetivo)}."
            }

            else -> null
        }
    }

    // ------------------------------------------- operaciones sobre texto

    private val I = setOf(RegexOption.IGNORE_CASE)
    private val CUANTAS_LETRAS = Regex("cuantas letras tiene (?:la palabra )?[«\"']?(.+?)[»\"']?$", I)
    private val CUANTAS_PALABRAS = Regex("cuantas palabras tiene (?:la frase )?[«\"']?(.+?)[»\"']?$", I)
    private val AL_REVES = Regex("(?:escribi|deci|pone|dame)?\\s*[«\"']?(.+?)[»\"']?\\s+al reves", I)
    private val PALINDROMO = Regex("[«\"']?(.+?)[»\"']?\\s+es (?:un )?palindromo|es palindromo [«\"']?(.+?)[»\"']?$", I)
    private val BORDES = charArrayOf(' ', '?', '.', '!', '¿', '¡')

    val operacionesTexto: (String, Rama) -> String? = { texto, _ ->
        val letras = CUANTAS_LETRAS.find(texto)
        val palabras = CUANTAS_PALABRAS.find(texto)
        val reves = AL_REVES.find(texto)
        val palindromo = PALINDROMO.find(Texto.normalizar(texto))

        when {
            letras != null -> {
                val palabra = letras.groupValues[1].trim(*BORDES)
                "«$palabra» tiene ${palabra.count { it.isLetter() }} letras."
            }

            palabras != null -> {
                val frase = palabras.groupValues[1].trim(*BORDES)
                "Esa frase tiene ${frase.split(Regex("\\s+")).count { it.isNotEmpty() }} palabras."
            }

            reves != null -> {
                val palabra = reves.groupValues[1].trim(*BORDES)
                if (palabra.isEmpty()) null else "«$palabra» al revés es «${palabra.reversed()}»."
            }

            palindromo != null -> {
                val palabra = palindromo.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                    ?.trim(*BORDES).orEmpty()
                val limpio = Texto.normalizar(palabra).replace(" ", "")
                if (limpio.isEmpty()) null
                else if (limpio == limpio.reversed())
                    "Sí, «$palabra» es un palíndromo: se lee igual en los dos sentidos."
                else "No, «$palabra» no es un palíndromo."
            }

            else -> null
        }
    }
}
