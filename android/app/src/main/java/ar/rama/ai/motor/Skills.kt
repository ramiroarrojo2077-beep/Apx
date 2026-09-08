package ar.rama.ai.motor

import java.util.Calendar

/**
 * Habilidades deterministas de Rama.
 *
 * Cada skill es `(texto, rama) -> String?`. Devolver `null` significa "esto no
 * es para mí, que siga el motor de intenciones".
 */
object Skills {

    val DIAS = arrayOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
    val MESES = arrayOf(
        "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio",
        "agosto", "septiembre", "octubre", "noviembre", "diciembre",
    )

    private val I = setOf(RegexOption.IGNORE_CASE)

    // ------------------------------------------------------------ cálculo

    private val EXPRESION = Regex("^[\\s\\d+\\-*/%^().,]+$")
    private val PISTA_CALCULO = Regex(
        "\\b(cuanto es|cuanto da|calcula|calcular|calculame|resultado de|suma|resta|" +
            "multiplica|divide)\\b"
    )
    private val PORCENTAJE = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:%|por ?ciento)\\s*de\\s*(\\d+(?:\\.\\d+)?)")
    private val RAIZ = Regex("raiz(?: cuadrada)?(?: de)?\\s*(\\d+(?:\\.\\d+)?)")
    private val SOLO_NUMEROS = Regex("[\\d+\\-*/%().\\s]*\\d[\\d+\\-*/%().\\s]*")
    private val ARITMETICA_LIMPIA = Regex("^[\\s\\d+\\-*/().]+$")
    private val TIENE_OPERADOR = Regex("[-+*/%]")
    private val PREFIJO_CALCULO = Regex("^\\s*(?:cuanto (?:es|da)|calcula\\w*)\\s*")

    /** Operadores escritos con palabras: "2 mas 2", "10 dividido 4". */
    private val PALABRAS_OPERADOR = listOf(
        Regex("\\b(?:mas|sumado a)\\b") to "+",
        Regex("\\b(?:menos|restado)\\b") to "-",
        Regex("\\b(?:multiplicado por|por)\\b") to "*",
        Regex("\\b(?:dividido(?: por| entre)?|entre)\\b") to "/",
        Regex("\\b(?:elevado a(?: la)?)\\b") to "^",
    )

    private fun traducirOperadores(texto: String): String {
        var salida = texto
        for ((patron, simbolo) in PALABRAS_OPERADOR) salida = patron.replace(salida, simbolo)
        return salida
    }

    val calculadora: (String, Rama) -> String? = { texto, _ ->
        val plano = Texto.normalizar(texto)
        // El % y el punto decimal no sobreviven a normalizar(), así que para
        // las expresiones trabajamos sobre el texto crudo sin tildes.
        val literal = Texto.quitarAcentos(texto.lowercase()).replace(",", ".")
        val crudo = texto.trim().replace("×", "*").replace("÷", "/")

        val porcentaje = PORCENTAJE.find(literal)
        val raiz = RAIZ.find(literal)

        when {
            porcentaje != null -> {
                val pct = porcentaje.groupValues[1].toDouble()
                val total = porcentaje.groupValues[2].toDouble()
                "El ${Calculadora.formatear(pct)}% de ${Calculadora.formatear(total)} es " +
                    "${Calculadora.formatear(total * pct / 100)}."
            }

            raiz != null -> {
                val numero = raiz.groupValues[1].toDouble()
                "La raíz cuadrada de ${Calculadora.formatear(numero)} es " +
                    "${Calculadora.formatear(Math.sqrt(numero))}."
            }

            else -> {
                // "2 mas 2" es aritmética escrita en castellano.
                val enPalabras = if (plano.any { it.isDigit() } &&
                    PALABRAS_OPERADOR.any { it.first.containsMatchIn(plano) }
                ) {
                    traducirOperadores(PREFIJO_CALCULO.replace(plano, ""))
                } else null

                var resultado: String? = null
                if (enPalabras != null && ARITMETICA_LIMPIA.matches(enPalabras) &&
                    TIENE_OPERADOR.containsMatchIn(enPalabras)
                ) {
                    resultado = try {
                        "${enPalabras.trim()} = ${Calculadora.formatear(Calculadora.evaluar(enPalabras))}"
                    } catch (e: ErrorCalculo) {
                        null
                    }
                }

                if (resultado == null) {
                    var candidato = crudo
                    if (PISTA_CALCULO.containsMatchIn(plano)) {
                        // "cuánto es 12*7?" -> nos quedamos con la parte aritmética.
                        candidato = SOLO_NUMEROS.findAll(crudo)
                            .map { it.value }
                            .maxByOrNull { it.length }
                            ?.trim() ?: ""
                    } else if (!EXPRESION.matches(crudo) || !crudo.any { it.isDigit() }) {
                        candidato = ""
                    }
                    candidato = candidato.trimEnd('?', '¿', '!', '¡', '.', ' ')
                    if (candidato.isNotEmpty() && TIENE_OPERADOR.containsMatchIn(candidato)) {
                        resultado = try {
                            "${candidato.trim()} = ${Calculadora.formatear(Calculadora.evaluar(candidato))}"
                        } catch (e: ErrorCalculo) {
                            if (e.message?.contains("cero") == true) {
                                "No se puede dividir por cero. Es la única regla que la matemática no negocia."
                            } else null
                        }
                    }
                }
                resultado
            }
        }
    }

    // -------------------------------------------------------- fecha y hora

    private val PIDE_HORA = Regex("\\bque hora es\\b|\\bhora actual\\b|\\bdame la hora\\b")
    private val PIDE_FECHA = Regex(
        "\\bque (dia|fecha) es hoy\\b|\\bfecha de hoy\\b|\\bque dia estamos\\b|^que (dia|fecha) es$"
    )

    val fechaHora: (String, Rama) -> String? = { texto, _ ->
        val plano = Texto.normalizar(texto)
        val ahora = Calendar.getInstance()
        when {
            PIDE_HORA.containsMatchIn(plano) -> {
                val hora = ahora.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                val minuto = ahora.get(Calendar.MINUTE).toString().padStart(2, '0')
                "Son las $hora:$minuto."
            }
            PIDE_FECHA.containsMatchIn(plano) -> {
                // Calendar.MONDAY es 2: lo llevamos a un índice 0..6 desde lunes.
                val dia = DIAS[(ahora.get(Calendar.DAY_OF_WEEK) + 5) % 7]
                "Hoy es $dia ${ahora.get(Calendar.DAY_OF_MONTH)} de " +
                    "${MESES[ahora.get(Calendar.MONTH)]} de ${ahora.get(Calendar.YEAR)}."
            }
            else -> null
        }
    }

    // ------------------------------------------------------------- nombre

    private val ME_LLAMO = Regex(
        "\\b(?:me llamo|mi nombre es|soy)\\s+([a-zñáéíóúü]+(?:\\s+[a-zñáéíóúü]+)?)\\b", I
    )
    private val COMO_ME_LLAMO = Regex("\\b(como me llamo|cual es mi nombre|sabes mi nombre)\\b")
    private val NO_NOMBRES = setOf("yo", "un", "una", "el", "la", "de", "que", "muy", "tu", "vos")

    val nombre: (String, Rama) -> String? = { texto, rama ->
        val plano = Texto.normalizar(texto)
        if (COMO_ME_LLAMO.containsMatchIn(plano)) {
            val guardado = rama.memoria.recuerdo("nombre")
            if (guardado != null) "Te llamás $guardado. No me olvido de esas cosas."
            else "Todavía no me dijiste tu nombre. Probá con «me llamo ...»."
        } else {
            val m = ME_LLAMO.find(texto)
            if (m == null) null else {
                val candidato = m.groupValues[1].trim()
                val primera = Texto.normalizar(candidato).split(" ").firstOrNull().orEmpty()
                if (primera.isEmpty() || primera in NO_NOMBRES || primera.length < 2) null
                else {
                    val propio = candidato.split(" ").joinToString(" ") { parte ->
                        parte.replaceFirstChar { it.uppercase() }
                    }
                    rama.memoria.recordar("nombre", propio)
                    "Un gusto, $propio. Ya lo anoté en mi memoria."
                }
            }
        }
    }

    // --------------------------------------------------------------- azar

    private val MONEDA = Regex("\\b(tira|tirar|lanza|lanzar|arroja)\\b.*\\bmoneda\\b|\\bcara o (ceca|cruz)\\b")
    private val DADO = Regex("\\bdado\\b")
    private val PIDE_TIRAR = Regex("\\b(tira|tirar|lanza|lanzar|dame)\\b")
    private val CARAS = Regex("\\bdado (?:de )?(\\d+)|\\bde (\\d+) caras\\b")
    private val ELEGIR = Regex("\\b(?:elegi|elige|escoge|decidi|decide)\\b(?: entre)?\\s+(.+)")
    private val SEPARADOR_OPCIONES = Regex("\\bo\\b|,|/")

    val azar: (String, Rama) -> String? = { texto, rama ->
        val plano = Texto.normalizar(texto)
        val elegir = ELEGIR.find(plano)
        when {
            MONEDA.containsMatchIn(plano) ->
                "Salió ${if (rama.azar.nextBoolean()) "cara" else "ceca"}."

            DADO.containsMatchIn(plano) && PIDE_TIRAR.containsMatchIn(plano) -> {
                val m = CARAS.find(plano)
                val declaradas = m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
                val caras = (declaradas ?: 6).coerceIn(2, 1000)
                "Tiré un dado de $caras caras y salió ${rama.azar.nextInt(1, caras + 1)}."
            }

            elegir != null -> {
                val opciones = elegir.groupValues[1]
                    .split(SEPARADOR_OPCIONES)
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (opciones.size >= 2) "Yo iría por: ${opciones[rama.azar.nextInt(opciones.size)]}."
                else null
            }

            else -> null
        }
    }

    // -------------------------------------------------------- aprendizaje

    private val APRENDE = Regex(
        "^\\s*(?:aprende|aprendé|recorda|recordá|enseñate)\\s*[:,]?\\s*(.+?)\\s*(?:=|\\||->|=>)\\s*(.+)$", I
    )
    private val OLVIDA = Regex("^\\s*olvid[aá](?:te de)?\\s*[:,]?\\s*(.+)$", I)
    private val DICTA_RESPUESTA = Regex(
        "^\\s*(?:se dice|responde|contesta|la respuesta es)\\s*[:,]?\\s*(.+)$", I
    )

    val aprendizaje: (String, Rama) -> String? = { texto, rama ->
        val aprende = APRENDE.find(texto)
        val olvida = if (aprende == null) OLVIDA.find(texto) else null
        when {
            aprende != null -> {
                val pregunta = aprende.groupValues[1].trim()
                val respuesta = aprende.groupValues[2].trim()
                rama.memoria.aprender(pregunta, respuesta)
                rama.reindexar()
                "Listo, aprendí que «$pregunta» → «$respuesta»."
            }

            olvida != null -> {
                val objetivo = olvida.groupValues[1].trim()
                if (rama.memoria.olvidar(objetivo)) {
                    rama.reindexar()
                    "Borrado. Ya no recuerdo nada sobre «$objetivo»."
                } else "No tenía nada guardado sobre «$objetivo»."
            }

            else -> {
                // "responde: ..." corrige la última pregunta: sirve tras un "no sé"
                // y también para enmendar una respuesta que no te convenció.
                val dictada = DICTA_RESPUESTA.find(texto)
                val pendiente = rama.memoria.ultimaPreguntaSinRespuesta
                    ?: rama.memoria.historial.filter { it.first == "usuario" }
                        .let { if (it.size >= 2) it[it.size - 2].second else null }
                if (dictada != null && pendiente != null) {
                    rama.memoria.aprender(pendiente, dictada.groupValues[1])
                    rama.memoria.ultimaPreguntaSinRespuesta = null
                    rama.reindexar()
                    "Gracias, lo guardé. La próxima que me preguntes «$pendiente» voy a saber."
                } else null
            }
        }
    }

    // ------------------------------------------------ memoria de la charla

    private val QUE_DIJE = Regex("\\bque (te dije|dije) (antes|recien)\\b|\\bque hablamos\\b")
    private val QUE_SABES = Regex("\\bcuanto (sabes|aprendiste)\\b|\\bque aprendiste\\b")

    val memoriaConversacion: (String, Rama) -> String? = { texto, rama ->
        val plano = Texto.normalizar(texto)
        when {
            QUE_DIJE.containsMatchIn(plano) -> {
                val mios = rama.memoria.historial.filter { it.first == "usuario" }
                if (mios.size >= 2) "Lo último que me dijiste antes de esto fue: «${mios[mios.size - 2].second}»."
                else "Todavía no hablamos lo suficiente como para tener pasado."
            }

            QUE_SABES.containsMatchIn(plano) -> {
                val n = rama.memoria.aprendido.size
                if (n == 0) {
                    "Manejo ${rama.totalIntenciones} temas de base y todavía no me enseñaste nada nuevo."
                } else {
                    val ejemplos = rama.memoria.aprendido.takeLast(3)
                        .joinToString(", ") { "«${it.pregunta}»" }
                    "Manejo ${rama.totalIntenciones} temas de base y $n cosas que me enseñaste vos, " +
                        "por ejemplo: $ejemplos."
                }
            }

            else -> null
        }
    }

    /**
     * Los comandos de control van antes que todo, incluso antes de lo
     * aprendido: si no, «olvidá x» podría ser respondido por el propio «x».
     */
    val COMANDOS: List<Pair<String, (String, Rama) -> String?>> = listOf(
        "aprendizaje" to aprendizaje,
    )

    /** El orden importa: lo más específico primero. */
    val TODAS: List<Pair<String, (String, Rama) -> String?>> = listOf(
        "nombre" to nombre,
        "fecha y hora" to fechaHora,
        "calendario" to SkillsDatos.calendario,
        "geografía" to SkillsDatos.geografia,
        "conversión de unidades" to SkillsDatos.conversiones,
        "operaciones de texto" to SkillsDatos.operacionesTexto,
        "azar" to azar,
        "memoria de la charla" to memoriaConversacion,
        "calculadora" to calculadora,
    )
}
