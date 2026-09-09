package ar.rama.ai

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.rama.ai.motor.Catalogo
import ar.rama.ai.motor.Descargador
import ar.rama.ai.motor.Llama
import ar.rama.ai.motor.ModeloDisponible
import java.io.File
import java.util.concurrent.Executors

/**
 * El panel donde elegís qué modelo usa Rama.
 *
 * Los pesos no vienen dentro del APK: son cientos de megas y no todos quieren
 * el mismo compromiso. La descarga la hace el gestor del sistema, así que
 * sigue aunque salgas de la app o apagues la pantalla.
 */
class PantallaModelos(
    private val actividad: Activity,
    private val modeloActivo: () -> File?,
    private val alUsar: (File) -> Unit,
    private val alBorrar: (File) -> Unit,
    private val alImportar: () -> Unit,
    private val alCopiar: (String) -> Unit,
) {

    private val descargas = DescargaEnSegundoPlano(actividad)

    /** Los gigas de RAM del teléfono, para saber qué modelo entra de verdad. */
    private val ramDelTelefono: Int by lazy {
        try {
            val gestor = actividad.getSystemService(Activity.ACTIVITY_SERVICE) as android.app.ActivityManager
            val info = android.app.ActivityManager.MemoryInfo()
            gestor.getMemoryInfo(info)
            Math.round(info.totalMem / 1024f / 1024f / 1024f)
        } catch (e: Exception) {
            0
        }
    }
    private val trabajador = Executors.newSingleThreadExecutor { tarea ->
        Thread(tarea, "rama-descarga").apply { isDaemon = true }
    }
    private val principal = Handler(Looper.getMainLooper())
    private val tarjetas = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
    private var vigilando = false

    val vista: View = construir()

    fun mostrar() {
        refrescar()
        vista.visibility = View.VISIBLE
        vigilar()
    }

    fun ocultar() {
        vista.visibility = View.GONE
    }

    val visible: Boolean get() = vista.visibility == View.VISIBLE

    /** El archivo del modelo, esté donde esté: descargado o importado. */
    fun archivoDe(modelo: ModeloDisponible): File = descargas.archivoDe(modelo)

    // ------------------------------------------------------------ armado

    private fun construir(): View {
        val fondo = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Paleta.FONDO)
            visibility = View.GONE
            isClickable = true
            fitsSystemWindows = true
        }

        fondo.addView(Hoja.cabecera(actividad, "Modelos") { ocultar() })
        fondo.addView(actividad.divisor())

        val desplazable = ScrollView(actividad).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(
                actividad.dp(Espacio.L), actividad.dp(Espacio.L),
                actividad.dp(Espacio.L), actividad.dp(Espacio.XL),
            )
        }
        val columna = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        columna.addView(
            TextView(actividad).estilo(Tipo.SECUNDARIO, Paleta.TEXTO_2, interlineado = 1.45f).apply {
                text = "Rama genera sus respuestas con un modelo que corre acá adentro, sin " +
                    "pasar por la IA de nadie. La descarga la hace el gestor del sistema: " +
                    "podés salir de la app y sigue bajando."
                setPadding(0, 0, 0, actividad.dp(Espacio.L))
            }
        )
        columna.addView(tarjetaDeMemoria())
        columna.addView(
            TextView(actividad).apply { text = "Del más liviano al más capaz" }.rotulo().apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = actividad.dp(Espacio.M) }
            }
        )
        columna.addView(tarjetas)
        columna.addView(construirImportar())
        desplazable.addView(columna)
        fondo.addView(desplazable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return fondo
    }

    /** Cuánta memoria tiene el teléfono: decide qué modelos tienen sentido. */
    private fun tarjetaDeMemoria(): View {
        val tarjeta = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fondoRedondeado(
                Paleta.SUPERFICIE, actividad.dp(Radio.MEDIO).toFloat(), Paleta.BORDE, actividad.dp(1f),
            )
            setPadding(
                actividad.dp(Espacio.L - 2f), actividad.dp(Espacio.M),
                actividad.dp(Espacio.L - 2f), actividad.dp(Espacio.M),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = actividad.dp(Espacio.XL) }
        }
        tarjeta.addView(
            actividad.punto(if (ramDelTelefono > 0) Paleta.ACENTO else Paleta.AVISO, 7f),
            LinearLayout.LayoutParams(actividad.dp(7f), actividad.dp(7f)).apply {
                rightMargin = actividad.dp(Espacio.M)
            },
        )
        tarjeta.addView(
            TextView(actividad).estilo(Tipo.ETIQUETA + 0.5f, Paleta.TEXTO_2, interlineado = 1.35f).apply {
                text = if (ramDelTelefono > 0) {
                    "Tu teléfono tiene ~$ramDelTelefono GB de RAM. Los que piden más quedan " +
                        "atenuados: se pueden bajar igual, pero probablemente no carguen."
                } else {
                    "No pude leer la memoria de tu teléfono."
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        return tarjeta
    }

    private fun construirImportar(): View {
        val tarjeta = tarjetaVacia()
        tarjeta.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = actividad.dp(Espacio.S) }
        tarjeta.addView(
            TextView(actividad).estilo(Tipo.SUBTITULO, Paleta.TEXTO, negrita = true, interlineado = 1f).apply {
                text = "Si la descarga no anda"
            }
        )
        tarjeta.addView(
            TextView(actividad).estilo(Tipo.SECUNDARIO, Paleta.TEXTO_2, interlineado = 1.45f).apply {
                text = "Copiá el enlace de cualquier modelo, pegalo en el navegador y bajalo " +
                    "desde ahí. Después volvé y elegí el archivo con el botón de abajo. " +
                    "Tiene que ser un .gguf."
                setPadding(0, actividad.dp(Espacio.XS + 1f), 0, actividad.dp(Espacio.M))
            }
        )
        tarjeta.addView(
            actividad.boton("Elegir un .gguf del teléfono") { alImportar() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
        )
        return tarjeta
    }

    // --------------------------------------------------------- contenido

    fun refrescar() {
        tarjetas.removeAllViews()

        if (!Llama.disponible) {
            tarjetas.addView(
                aviso(
                    "No puedo generar texto",
                    "${Llama.motivoNoDisponible}\n\nRama sigue funcionando con su base.",
                    Paleta.ERROR,
                    Paleta.ERROR_TENUE,
                )
            )
        }
        for (modelo in Catalogo.MODELOS) tarjetas.addView(tarjetaDe(modelo))
    }

    /** Una tarjeta con franja de color al costado, para lo que pide atención. */
    private fun aviso(titulo: String, cuerpo: String, color: Int, fondo: Int): View {
        val tarjeta = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoConFranja(fondo, color, actividad.dp(Radio.MEDIO).toFloat(), actividad.dp(3f))
            setPadding(
                actividad.dp(Espacio.L), actividad.dp(Espacio.M),
                actividad.dp(Espacio.L - 2f), actividad.dp(Espacio.M),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = actividad.dp(Espacio.M) }
        }
        tarjeta.addView(
            TextView(actividad).estilo(Tipo.SECUNDARIO + 0.5f, color, negrita = true, interlineado = 1f).apply {
                text = titulo
            }
        )
        tarjeta.addView(
            TextView(actividad).estilo(Tipo.ETIQUETA + 0.5f, Paleta.TEXTO_2, interlineado = 1.4f).apply {
                text = cuerpo
                setPadding(0, actividad.dp(Espacio.XS + 1f), 0, 0)
            }
        )
        return tarjeta
    }

    /** Mientras haya algo bajando, la pantalla se actualiza sola. */
    private fun vigilar() {
        if (vigilando) return
        vigilando = true
        val latido = object : Runnable {
            override fun run() {
                if (!visible) {
                    vigilando = false
                    return
                }
                val activas = Catalogo.MODELOS.any { descargas.estado(it) is EstadoDescarga.EnCurso }
                refrescar()
                if (activas) {
                    principal.postDelayed(this, 1200)
                } else {
                    vigilando = false
                }
            }
        }
        principal.postDelayed(latido, 1200)
    }

    private fun tarjetaVacia(): LinearLayout = LinearLayout(actividad).apply {
        orientation = LinearLayout.VERTICAL
        background = fondoRedondeado(
            Paleta.SUPERFICIE, actividad.dp(Radio.GRANDE).toFloat(), Paleta.BORDE, actividad.dp(1f),
        )
        setPadding(
            actividad.dp(Espacio.L), actividad.dp(Espacio.L - 1f),
            actividad.dp(Espacio.L), actividad.dp(Espacio.L - 1f),
        )
    }

    private fun tarjetaDe(modelo: ModeloDisponible): View {
        val archivo = descargas.archivoDe(modelo)
        val estado = descargas.estado(modelo)
        val enUso = modeloActivo()?.absolutePath == archivo.absolutePath
        val entra = ramDelTelefono == 0 || Catalogo.ramNecesaria(modelo) <= ramDelTelefono

        val tarjeta = tarjetaVacia()
        if (!entra) tarjeta.alpha = 0.5f
        if (enUso) {
            tarjeta.background = fondoRedondeado(
                Paleta.ACENTO_TENUE, actividad.dp(Radio.GRANDE).toFloat(), Paleta.ACENTO, actividad.dp(1f),
            )
        }
        tarjeta.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = actividad.dp(Espacio.M) }

        val encabezado = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        encabezado.addView(
            TextView(actividad).estilo(Tipo.SUBTITULO, Paleta.TEXTO, negrita = true, interlineado = 1.15f).apply {
                text = modelo.nombre
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (enUso) encabezado.addView(sello("En uso", Paleta.ACENTO, Paleta.ACENTO_TENUE))
        else if (!entra) encabezado.addView(sello("No entra", Paleta.AVISO, Paleta.AVISO_TENUE))
        tarjeta.addView(encabezado)

        tarjeta.addView(
            TextView(actividad).estilo(Tipo.MICRO + 0.5f, Paleta.TEXTO_3, interlineado = 1f).apply {
                val peso = if (modelo.bytesAproximados >= 1024L * 1024 * 1024) {
                    "%.1f GB".format(modelo.bytesAproximados / 1024.0 / 1024 / 1024)
                } else {
                    "%.0f MB".format(modelo.bytesAproximados / 1024.0 / 1024)
                }
                text = "${modelo.familia}  ·  $peso  ·  necesita ~${modelo.ramRecomendada}"
                setPadding(0, actividad.dp(Espacio.XS + 1f), 0, 0)
            }
        )
        tarjeta.addView(medidor("Precisión", modelo.precision))
        tarjeta.addView(medidor("Velocidad", modelo.velocidad))
        tarjeta.addView(
            TextView(actividad).estilo(Tipo.SECUNDARIO, Paleta.TEXTO_2, interlineado = 1.45f).apply {
                text = modelo.descripcion
                setPadding(0, actividad.dp(Espacio.M), 0, actividad.dp(Espacio.M + 2f))
            }
        )

        when (estado) {
            is EstadoDescarga.EnCurso -> {
                tarjeta.addView(barraDeProgreso(estado))
                tarjeta.addView(
                    TextView(actividad).estilo(Tipo.ETIQUETA, Paleta.TEXTO_2, interlineado = 1f).apply {
                        val hechos = estado.bytes / 1024.0 / 1024
                        val totales = estado.totales / 1024.0 / 1024
                        text = when {
                            estado.enPausa -> "En pausa · esperando conexión"
                            estado.totales > 0 -> "%.0f de %.0f MB · %.0f%%".format(
                                hechos, totales, estado.fraccion * 100,
                            )
                            else -> "%.0f MB descargados".format(hechos)
                        }
                        setPadding(0, actividad.dp(Espacio.S), 0, actividad.dp(Espacio.M))
                    }
                )
                tarjeta.addView(
                    filaDeBotones(
                        actividad.boton("Cancelar") {
                            descargas.cancelar(modelo)
                            refrescar()
                        }
                    )
                )
            }

            is EstadoDescarga.Terminada -> {
                if (enUso) {
                    tarjeta.addView(
                        filaDeBotones(
                            actividad.boton("Quitar de la memoria") {
                                alBorrar(archivo)
                                refrescar()
                            }
                        )
                    )
                } else {
                    tarjeta.addView(
                        filaDeBotones(
                            actividad.boton("Usar este", principal = true) {
                                alUsar(estado.archivo)
                                ocultar()
                            },
                            actividad.boton("Borrar", color = Paleta.ERROR) {
                                estado.archivo.delete()
                                refrescar()
                            },
                        )
                    )
                }
            }

            is EstadoDescarga.Fallo -> {
                tarjeta.addView(
                    TextView(actividad).estilo(Tipo.ETIQUETA + 0.5f, Paleta.ERROR, interlineado = 1.35f).apply {
                        text = "La descarga falló: ${estado.motivo}"
                        setPadding(0, 0, 0, actividad.dp(Espacio.M))
                    }
                )
                tarjeta.addView(
                    filaDeBotones(
                        actividad.boton("Reintentar", principal = true) { descargar(modelo) },
                        actividad.boton("Copiar enlace") { copiarEnlace(modelo) },
                    )
                )
            }

            EstadoDescarga.Ninguna -> tarjeta.addView(
                filaDeBotones(
                    actividad.boton("Descargar", principal = true) { descargar(modelo) },
                    actividad.boton("Copiar enlace") { copiarEnlace(modelo) },
                )
            )
        }
        return tarjeta
    }

    /** El estado del modelo en dos palabras, arriba a la derecha. */
    private fun sello(texto: String, color: Int, fondo: Int): TextView =
        TextView(actividad).apply { text = texto }.rotulo(color).apply {
            padding(actividad.dp(Espacio.S + 1f), actividad.dp(Espacio.XS + 1f))
            background = fondoRedondeado(fondo, actividad.dp(Radio.CHICO).toFloat(), color, actividad.dp(1f))
        }

    /**
     * Precisión y velocidad como una barra y no como cuadraditos de texto.
     *
     * Antes eran «▰▰▰▱▱» en monoespaciada, que es una barra dibujada con
     * letras. Dibujada de verdad se compara de un vistazo entre tarjetas.
     */
    private fun medidor(etiqueta: String, valor: Int): View {
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, actividad.dp(Espacio.S), 0, 0)
        }
        fila.addView(
            TextView(actividad).estilo(Tipo.MICRO, Paleta.TEXTO_3, interlineado = 1f).apply {
                text = etiqueta
                width = actividad.dp(58f)
            }
        )
        val riel = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (i in 1..ModeloDisponible.ESCALA) {
            val encendido = i <= valor
            riel.addView(
                View(actividad).apply {
                    background = fondoRedondeado(
                        if (encendido) Paleta.ACENTO else Paleta.SUPERFICIE_ALTA,
                        actividad.dp(2f).toFloat(),
                    )
                },
                LinearLayout.LayoutParams(actividad.dp(26f), actividad.dp(4f)).apply {
                    rightMargin = actividad.dp(3f)
                },
            )
        }
        fila.addView(riel)
        return fila
    }

    /** Una barra de progreso propia: la del sistema no respeta la paleta. */
    private fun barraDeProgreso(estado: EstadoDescarga.EnCurso): View {
        val riel = LinearLayout(actividad).apply {
            background = fondoRedondeado(Paleta.SUPERFICIE_ALTA, actividad.dp(3f).toFloat())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, actividad.dp(6f),
            )
        }
        // Con el total desconocido mostramos un tramo fijo: mentir con una
        // barra al 90% es peor que admitir que no se sabe cuánto falta.
        val fraccion = if (estado.totales > 0) estado.fraccion.coerceIn(0.02f, 1f) else 0.08f
        riel.addView(
            View(actividad).apply {
                background = fondoRedondeado(
                    if (estado.enPausa) Paleta.AVISO else Paleta.ACENTO,
                    actividad.dp(3f).toFloat(),
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, fraccion),
        )
        riel.addView(
            View(actividad),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - fraccion),
        )
        return riel
    }

    // -------------------------------------------------------- acciones

    private fun descargar(modelo: ModeloDisponible) {
        val aviso = TextView(actividad).estilo(Tipo.ETIQUETA, Paleta.TEXTO_2, interlineado = 1f).apply {
            text = "Buscando el archivo…"
            setPadding(0, 0, 0, actividad.dp(Espacio.M))
        }
        tarjetas.addView(aviso, 0)

        trabajador.execute {
            val (url, intentos) = Descargador.resolver(modelo)
            principal.post {
                tarjetas.removeView(aviso)
                if (url == null) {
                    mostrarFalloDeBusqueda(modelo, intentos)
                    return@post
                }
                if (descargas.encolar(modelo, url) < 0) {
                    mostrarFalloDeBusqueda(modelo, listOf("el gestor de descargas del sistema no aceptó el pedido"))
                    return@post
                }
                refrescar()
                vigilar()
            }
        }
    }

    private fun mostrarFalloDeBusqueda(modelo: ModeloDisponible, intentos: List<String>) {
        tarjetas.addView(
            aviso(
                "No encontré «${modelo.nombre}»",
                intentos.joinToString("\n") +
                    "\n\nProbá con otro modelo, o copiá el enlace y bajalo desde el navegador.",
                Paleta.ERROR,
                Paleta.ERROR_TENUE,
            ),
            0,
        )
    }

    /** El enlace directo, para bajarlo con el navegador si el gestor falla. */
    private fun copiarEnlace(modelo: ModeloDisponible) {
        alCopiar(Descargador.urlDeArchivo(modelo.repositorio, modelo.archivo))
    }

    private fun filaDeBotones(vararg botones: TextView): View =
        LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            botones.forEachIndexed { i, boton ->
                addView(
                    boton,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { if (i < botones.size - 1) rightMargin = actividad.dp(Espacio.S) },
                )
            }
        }
}
