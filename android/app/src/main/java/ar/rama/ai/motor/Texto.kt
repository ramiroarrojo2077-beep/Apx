package ar.rama.ai.motor

import java.text.Normalizer

/** Procesamiento de texto en español: normalización, tokenización y stemming. */
object Texto {

    /** Palabras vacías: aportan poco significado y sólo ensucian la similitud. */
    val STOPWORDS: Set<String> = setOf(
        "a", "al", "algo", "algun", "alguna", "algunas", "alguno", "algunos", "ante",
        "aqui", "asi", "aunque", "cada", "como", "con", "contra", "cual", "cuales",
        "cuando", "de", "del", "desde", "donde", "dos", "el", "ella", "ellas", "ello",
        "ellos", "en", "entre", "era", "eran", "eres", "es", "esa", "esas", "ese",
        "eso", "esos", "esta", "estan", "estas", "este", "esto", "estos", "estoy",
        "fue", "fueron", "ha", "han", "has", "hasta", "hay", "he", "la", "las", "le",
        "les", "lo", "los", "mas", "me", "mi", "mis", "mucho", "muy", "nada", "ni",
        "no", "nos", "nosotros", "o", "os", "otra", "otro", "para", "pero", "poco",
        "por", "porque", "que", "quien", "se", "segun", "ser", "si", "sin", "sobre",
        "solo", "son", "soy", "su", "sus", "tambien", "te", "tener", "tengo", "ti",
        "tiene", "tienen", "todo", "todos", "tu", "tus", "un", "una", "uno", "unos",
        "vos", "y", "ya", "yo",
    )

    /** Sufijos ordenados de más largo a más corto: el primero que encaje gana. */
    private val SUFIJOS = arrayOf(
        "amientos", "imientos", "amiento", "imiento", "aciones", "adoras", "adores",
        "ancias", "logias", "ucions", "encias", "amente", "aciona", "adora", "ador",
        "ancia", "logia", "ucion", "encia", "mente", "anza", "icos", "icas", "ismo",
        "able", "ible", "ista", "osos", "osas", "ico", "ica", "oso", "osa", "iva",
        "ivo", "ando", "iendo", "ados", "idos", "ada", "ido", "ar", "er", "ir", "es",
        "as", "os", "s",
    )

    private const val VIRGULILLA = '\u0303'  // combinante U+0303, la de la ñ
    private val NO_ALFANUMERICO = Regex("[^a-z0-9ñ\\s]+")
    private val ESPACIOS = Regex("\\s+")

    /**
     * Elimina tildes y diéresis pero conserva la ñ.
     *
     * Descompone en NFD y descarta las marcas diacríticas, salvo la virgulilla
     * cuando corona una n: ahí forma una letra propia del español, no un acento.
     */
    fun quitarAcentos(texto: String): String {
        val descompuesto = Normalizer.normalize(texto, Normalizer.Form.NFD)
        val salida = StringBuilder(descompuesto.length)
        for (c in descompuesto) {
            val anterior = salida.lastOrNull()
            if (c == VIRGULILLA && (anterior == 'n' || anterior == 'N')) {
                salida.append(c)
            } else if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) {
                salida.append(c)
            }
        }
        return Normalizer.normalize(salida, Normalizer.Form.NFC)
    }

    /** Pasa a minúsculas, quita acentos, signos y espacios sobrantes. */
    fun normalizar(texto: String): String {
        val plano = quitarAcentos(texto.lowercase())
        return ESPACIOS.replace(NO_ALFANUMERICO.replace(plano, " "), " ").trim()
    }

    /**
     * Stemmer ligero: recorta sufijos frecuentes del español.
     *
     * No pretende ser lingüísticamente exacto, sólo hacer que "programar",
     * "programando" y "programas" caigan en el mismo cubo.
     */
    fun raiz(palabra: String): String {
        if (palabra.length <= 4) return palabra
        for (sufijo in SUFIJOS) {
            if (palabra.endsWith(sufijo) && palabra.length - sufijo.length >= 3) {
                return palabra.substring(0, palabra.length - sufijo.length)
            }
        }
        return palabra
    }

    /** Convierte texto libre en la lista de tokens que usa el motor. */
    fun tokenizar(texto: String, conRaiz: Boolean = true, quitarVacias: Boolean = true): List<String> {
        var palabras = normalizar(texto).split(" ").filter { it.isNotEmpty() }
        if (quitarVacias) {
            val filtradas = palabras.filter { it !in STOPWORDS }
            // Si la frase era toda stopwords ("¿cómo estás?"), no la vaciamos.
            if (filtradas.isNotEmpty()) palabras = filtradas
        }
        return if (conRaiz) palabras.map { raiz(it) } else palabras
    }
}
