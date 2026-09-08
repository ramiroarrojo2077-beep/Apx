package ar.rama.ai.motor

/** Un modelo que Rama puede descargar y usar. */
data class ModeloDisponible(
    val id: String,
    val nombre: String,
    val repositorio: String,
    val archivo: String,
    val bytesAproximados: Long,
    val ramRecomendada: String,
    val descripcion: String,
)

/**
 * Los modelos que ofrece la app.
 *
 * Son GGUF publicados por sus autores; Rama los descarga y los corre, no los
 * consulta por internet. Elegir uno es elegir un compromiso entre tamaño,
 * velocidad y qué tan seguido se equivoca.
 */
object Catalogo {

    val MODELOS: List<ModeloDisponible> = listOf(
        ModeloDisponible(
            id = "qwen3-0.6b",
            nombre = "Qwen3 0.6B",
            repositorio = "Qwen/Qwen3-0.6B-GGUF",
            archivo = "Qwen3-0.6B-Q4_K_M.gguf",
            bytesAproximados = 400L * 1024 * 1024,
            ramRecomendada = "3 GB",
            descripcion = "Liviano y rápido: responde en pocos segundos en casi " +
                "cualquier teléfono. Escribe bien en español, pero se equivoca " +
                "seguido en datos. La búsqueda web es la que lo mantiene honesto.",
        ),
        ModeloDisponible(
            id = "qwen3-1.7b",
            nombre = "Qwen3 1.7B",
            repositorio = "Qwen/Qwen3-1.7B-GGUF",
            archivo = "Qwen3-1.7B-Q4_K_M.gguf",
            bytesAproximados = 1150L * 1024 * 1024,
            ramRecomendada = "6 GB",
            descripcion = "Bastante más coherente y con más conocimiento propio. " +
                "Pide un teléfono holgado: tarda más por respuesta y calienta. " +
                "Si el tuyo es modesto, mejor el chico.",
        ),
    )

    fun porId(id: String): ModeloDisponible? = MODELOS.firstOrNull { it.id == id }

    /** Nombre del archivo local donde se guarda cada modelo. */
    fun nombreLocal(modelo: ModeloDisponible): String = "${modelo.id}.gguf"
}
