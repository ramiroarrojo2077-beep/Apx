package ar.rama.ai.motor

/**
 * Una forma de conversar: cambia cómo escribe el modelo y cuánto se arriesga.
 *
 * No son personajes ni disfraces. Cada modo mueve cosas concretas: la
 * temperatura del muestreo, cuánto se extiende, y si sale a buscar a la web.
 */
data class Modo(
    val id: String,
    val nombre: String,
    val icono: String,
    val descripcion: String,
    val instruccion: String,
    val temperatura: Float,
    val topP: Float,
    val maxTokens: Int,
    val buscaEnWeb: Boolean,
)

object Modos {

    /**
     * Lo que vale para todos los modos.
     *
     * El idioma va primero y en mayúsculas a propósito: los modelos chicos
     * multilingües se van al inglés apenas pueden, y esto es lo que más lo
     * frena. El pedido de no mostrar el razonamiento se refuerza además
     * filtrando la salida, porque pedirlo no siempre alcanza.
     */
    val BASE = """
        Sos Rama, una IA que corre dentro del teléfono del usuario.

        1. Respondé SIEMPRE en español rioplatense, sea cual sea el idioma de
           la pregunta. Nunca en inglés ni en chino, ni mezclado.
        2. Escribí sólo la respuesta final, sin razonar en voz alta.
        3. Un DATO VERIFICADO es correcto: usalo tal cual.
        4. Con RESULTADOS DE BÚSQUEDA, respondé con eso y citá la fuente.
        5. Si no sabés, decilo. No inventes fechas, cifras, nombres ni enlaces.
    """.trimIndent()

    val CHARLA = Modo(
        id = "charla",
        nombre = "Charla",
        icono = "💬",
        descripcion = "Equilibrado. Contesta como en una conversación, sin dar vueltas.",
        instruccion = "Contestá en tono de conversación, natural y breve: dos o tres " +
            "oraciones salvo que el tema pida más.",
        temperatura = 0.7f,
        topP = 0.95f,
        maxTokens = 320,
        buscaEnWeb = true,
    )

    val PRECISO = Modo(
        id = "preciso",
        nombre = "Preciso",
        icono = "🎯",
        descripcion = "Va al dato. Se calla cuando no sabe y busca en la web más seguido.",
        instruccion = "Contestá de forma factual y corta. No adornes. Si no estás " +
            "seguro de un dato, decí que no lo sabés en vez de aproximar.",
        temperatura = 0.2f,
        topP = 0.9f,
        maxTokens = 280,
        buscaEnWeb = true,
    )

    val EXPLICAR = Modo(
        id = "explicar",
        nombre = "Explicar",
        icono = "📚",
        descripcion = "Desarrolla con ejemplos y paso a paso. Para entender algo, no para consultarlo.",
        instruccion = "Explicá con calma y en orden: primero la idea central en una " +
            "frase, después el desarrollo con un ejemplo concreto. Usá lenguaje " +
            "llano, sin tecnicismos innecesarios.",
        temperatura = 0.5f,
        topP = 0.95f,
        maxTokens = 700,
        buscaEnWeb = true,
    )

    val CREATIVO = Modo(
        id = "creativo",
        nombre = "Creativo",
        icono = "✨",
        descripcion = "Para escribir, imaginar y jugar. Se suelta más y no sale a buscar.",
        instruccion = "Escribí con libertad e imaginación: historias, ideas, juegos, " +
            "textos. Podés inventar todo lo que sea ficción, pero si te preguntan " +
            "por un hecho real seguí sin inventarlo.",
        temperatura = 1.0f,
        topP = 0.98f,
        maxTokens = 700,
        buscaEnWeb = false,
    )

    val AL_HUESO = Modo(
        id = "al-hueso",
        nombre = "Al hueso",
        icono = "⚡",
        descripcion = "Una o dos frases. Nada más.",
        instruccion = "Contestá en una o dos oraciones como máximo. Sin introducción, " +
            "sin cierre, sin repetir la pregunta. Sólo la respuesta.",
        temperatura = 0.4f,
        topP = 0.9f,
        maxTokens = 120,
        buscaEnWeb = true,
    )

    val TODOS = listOf(CHARLA, PRECISO, EXPLICAR, CREATIVO, AL_HUESO)

    val PREDETERMINADO = CHARLA

    fun porId(id: String?): Modo = TODOS.firstOrNull { it.id == id } ?: PREDETERMINADO

    /** El mensaje de sistema completo para un modo. */
    fun sistema(modo: Modo): String = "$BASE\n\n${modo.instruccion}"
}
