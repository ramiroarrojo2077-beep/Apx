package ar.rama.ai.motor

/** Dónde vive un archivo de modelo: repositorio y nombre dentro de él. */
data class Origen(val repositorio: String, val archivo: String)

/** Un modelo que Rama puede descargar y usar. */
data class ModeloDisponible(
    val id: String,
    val nombre: String,
    val bytesAproximados: Long,
    val ramRecomendada: String,
    val descripcion: String,
    /**
     * Varios lugares donde buscarlo, en orden.
     *
     * Los repositorios se renombran y reorganizan seguido; con un solo origen,
     * cualquier cambio deja la app sin poder bajar nada.
     */
    val origenes: List<Origen>,
) {
    val repositorio: String get() = origenes.first().repositorio
    val archivo: String get() = origenes.first().archivo
}

/**
 * Los modelos que ofrece la app.
 *
 * Son GGUF publicados por sus autores; Rama los descarga y los corre, no los
 * consulta por internet. Elegir uno es elegir un compromiso entre tamaño,
 * velocidad y qué tan seguido se equivoca.
 */
object Catalogo {

    val CHICO = ModeloDisponible(
        id = "qwen3-0.6b",
        nombre = "Qwen3 0.6B",
        bytesAproximados = 400L * 1024 * 1024,
        ramRecomendada = "3 GB",
        descripcion = "Liviano y rápido: responde en pocos segundos en casi cualquier " +
            "teléfono. Escribe bien en español, pero se equivoca seguido en datos. " +
            "La búsqueda web es la que lo mantiene honesto.",
        origenes = listOf(
            Origen("Qwen/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"),
            Origen("unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"),
            Origen("ggml-org/Qwen3-0.6B-GGUF", "Qwen3-0.6B-Q4_K_M.gguf"),
            Origen("bartowski/Qwen_Qwen3-0.6B-GGUF", "Qwen_Qwen3-0.6B-Q4_K_M.gguf"),
        ),
    )

    val MEDIANO = ModeloDisponible(
        id = "qwen3-1.7b",
        nombre = "Qwen3 1.7B",
        bytesAproximados = 1150L * 1024 * 1024,
        ramRecomendada = "6 GB",
        descripcion = "Bastante más coherente y con más conocimiento propio. Pide un " +
            "teléfono holgado: tarda más por respuesta y calienta. Si el tuyo es " +
            "modesto, mejor el chico.",
        origenes = listOf(
            Origen("Qwen/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
            Origen("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B-Q4_K_M.gguf"),
            Origen("bartowski/Qwen_Qwen3-1.7B-GGUF", "Qwen_Qwen3-1.7B-Q4_K_M.gguf"),
        ),
    )

    /** De otra familia, por si los Qwen no están disponibles. */
    val RESPALDO = ModeloDisponible(
        id = "llama32-1b",
        nombre = "Llama 3.2 1B",
        bytesAproximados = 810L * 1024 * 1024,
        ramRecomendada = "4 GB",
        descripcion = "Otra familia de modelos, por si los Qwen fallan al descargar. " +
            "Escribe bien en español y pide menos memoria que el mediano.",
        origenes = listOf(
            Origen("bartowski/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
            Origen("unsloth/Llama-3.2-1B-Instruct-GGUF", "Llama-3.2-1B-Instruct-Q4_K_M.gguf"),
        ),
    )

    val MODELOS: List<ModeloDisponible> = listOf(CHICO, MEDIANO, RESPALDO)

    fun porId(id: String): ModeloDisponible? = MODELOS.firstOrNull { it.id == id }

    /** Nombre del archivo local donde se guarda cada modelo. */
    fun nombreLocal(modelo: ModeloDisponible): String = "${modelo.id}.gguf"
}
