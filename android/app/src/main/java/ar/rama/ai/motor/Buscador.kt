package ar.rama.ai.motor

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Un resultado de búsqueda, ya limpio de HTML. */
data class Resultado(val titulo: String, val url: String, val resumen: String)

/**
 * Búsqueda web sin API key ni cuenta.
 *
 * Usa la versión HTML de DuckDuckGo, que no pide registro. Es lo único que
 * Rama consulta afuera, y sólo cuando la pregunta lo necesita: sirve para
 * que el modelo no invente datos que puede verificar.
 */
class Buscador(private val agente: String = AGENTE) {

    fun buscar(consulta: String, maximo: Int = 4): List<Resultado> {
        val html = descargar(consulta) ?: return emptyList()
        return parsear(html, maximo)
    }

    private fun descargar(consulta: String): String? = try {
        val direccion = "https://html.duckduckgo.com/html/?q=" +
            URLEncoder.encode(consulta, "UTF-8")
        val conexion = (URL(direccion).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", agente)
            setRequestProperty("Accept-Language", "es-AR,es;q=0.9")
        }
        if (conexion.responseCode in 200..299) {
            conexion.inputStream.bufferedReader().use { it.readText() }
        } else {
            null
        }
    } catch (e: IOException) {
        null
    } catch (e: Exception) {
        null
    }

    companion object {
        const val AGENTE = "Mozilla/5.0 (Linux; Android 12) RamaAI/2.0"

        private val ENLACE = Regex(
            """<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val RESUMEN = Regex(
            """<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        private val ETIQUETA = Regex("<[^>]+>")
        private val ESPACIOS = Regex("\\s+")
        private val ENTIDAD_NUMERICA = Regex("&#(x?)([0-9a-fA-F]+);")

        /** Las que aparecen de verdad en resultados en español. */
        private val ENTIDADES = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
            "nbsp" to " ", "hellip" to "…", "mdash" to "—", "ndash" to "–",
            "laquo" to "«", "raquo" to "»", "deg" to "°", "euro" to "€",
            "aacute" to "á", "eacute" to "é", "iacute" to "í", "oacute" to "ó",
            "uacute" to "ú", "ntilde" to "ñ", "uuml" to "ü",
            "Aacute" to "Á", "Eacute" to "É", "Iacute" to "Í", "Oacute" to "Ó",
            "Uacute" to "Ú", "Ntilde" to "Ñ", "Uuml" to "Ü",
            "iquest" to "¿", "iexcl" to "¡",
        )

        /**
         * Extrae los resultados del HTML.
         *
         * Se mantiene tolerante a propósito: si la página cambia de forma, es
         * preferible devolver menos resultados que romperse.
         */
        fun parsear(html: String, maximo: Int = 4): List<Resultado> {
            val enlaces = ENLACE.findAll(html).toList()
            val resumenes = RESUMEN.findAll(html).map { limpiar(it.groupValues[1]) }.toList()

            return enlaces.take(maximo).mapIndexedNotNull { i, coincidencia ->
                val titulo = limpiar(coincidencia.groupValues[2])
                val url = descifrarEnlace(coincidencia.groupValues[1])
                if (titulo.isBlank() || url.isBlank()) null
                else Resultado(titulo, url, resumenes.getOrElse(i) { "" })
            }
        }

        /** DuckDuckGo envuelve los enlaces en un redirector; sacamos el destino. */
        fun descifrarEnlace(crudo: String): String {
            val enlace = crudo.replace("&amp;", "&")
            val marca = "uddg="
            val desde = enlace.indexOf(marca)
            if (desde < 0) {
                return if (enlace.startsWith("//")) "https:$enlace" else enlace
            }
            val hasta = enlace.indexOf('&', desde).let { if (it < 0) enlace.length else it }
            return try {
                URLDecoder.decode(enlace.substring(desde + marca.length, hasta), "UTF-8")
            } catch (e: Exception) {
                enlace
            }
        }

        fun limpiar(html: String): String {
            var texto = ETIQUETA.replace(html, "")
            texto = ENTIDAD_NUMERICA.replace(texto) { coincidencia ->
                val base = if (coincidencia.groupValues[1].isEmpty()) 10 else 16
                val codigo = coincidencia.groupValues[2].toIntOrNull(base)
                if (codigo != null && codigo in 1..0x10FFFF) String(Character.toChars(codigo))
                else coincidencia.value
            }
            for ((nombre, simbolo) in ENTIDADES) texto = texto.replace("&$nombre;", simbolo)
            return ESPACIOS.replace(texto, " ").trim()
        }
    }
}
