package ar.rama.ai.motor

import java.io.File

/**
 * Cuántos hilos conviene darle al modelo.
 *
 * Un teléfono no tiene ocho núcleos iguales: tiene dos o cuatro rápidos y el
 * resto lentos, pensados para que el reloj de la pantalla no gaste batería. Un
 * núcleo lento corre a un tercio de la velocidad del rápido, y ggml reparte
 * cada multiplicación en partes iguales entre todos los hilos y después espera
 * a que terminen todos. Así que sumar los lentos no acelera: frena, porque los
 * rápidos se quedan esperando al que quedó último.
 *
 * Por eso contamos sólo los núcleos de la frecuencia más alta, leyendo lo que
 * el propio kernel publica en /sys. Si esa lectura no está disponible —hay
 * teléfonos que la esconden— volvemos a una cuenta prudente.
 */
object Nucleos {

    /** Dónde el kernel publica la frecuencia máxima de cada núcleo. */
    private const val RUTA = "/sys/devices/system/cpu"

    /**
     * Las frecuencias máximas de cada núcleo, en kHz, en el orden del sistema.
     * Vacío si no se pueden leer.
     */
    fun frecuencias(raiz: File = File(RUTA)): List<Long> {
        val cpus = raiz.listFiles()
            ?.filter { it.name.matches(Regex("cpu\\d+")) }
            ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
            ?: return emptyList()

        val frecuencias = cpus.map { cpu ->
            val archivo = File(cpu, "cpufreq/cpuinfo_max_freq")
            runCatching { archivo.readText().trim().toLong() }.getOrDefault(0L)
        }
        return if (frecuencias.any { it > 0 }) frecuencias else emptyList()
    }

    /**
     * Cuántos núcleos comparten la frecuencia más alta.
     *
     * Tomamos como "rápido" a todo lo que llegue al 85% del tope: en varios
     * procesadores el núcleo principal va un poco más alto que los otros tres
     * grandes, y dejarlo solo sería peor que usar los cuatro.
     */
    fun rapidos(frecuencias: List<Long>): Int {
        val tope = frecuencias.maxOrNull() ?: return 0
        if (tope <= 0) return 0
        val piso = tope * 85 / 100
        return frecuencias.count { it >= piso }
    }

    /**
     * Los hilos con los que abrir el modelo.
     *
     * Nunca menos de dos ni más de seis: con uno solo la espera se hace eterna,
     * y más de seis en un teléfono significa que ya estamos usando los núcleos
     * lentos o cocinando el aparato hasta que el sistema baje la frecuencia.
     */
    fun recomendados(
        frecuencias: List<Long> = frecuencias(),
        total: Int = Runtime.getRuntime().availableProcessors(),
    ): Int {
        val rapidos = rapidos(frecuencias)
        // Sin lectura de /sys: dejamos dos núcleos libres para la interfaz.
        if (rapidos == 0) return (total - 2).coerceIn(2, 6)
        // Si son todos iguales, no hay núcleos lentos que evitar; el que manda
        // es el calor, y ahí sí conviene dejar aire.
        if (rapidos == frecuencias.size) return (total - 2).coerceIn(2, 6)
        return rapidos.coerceIn(2, 6)
    }
}
