package ar.rama.ai

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
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
        }

        val cabecera = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(actividad.dp(16f), actividad.dp(14f), actividad.dp(12f), actividad.dp(12f))
        }
        cabecera.addView(
            TextView(actividad).estilo(19f, Paleta.TEXTO, negrita = true).apply {
                text = "Modelo de lenguaje"
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        cabecera.addView(
            TextView(actividad).estilo(20f, Paleta.TENUE, negrita = true).apply {
                text = "✕"
                gravity = Gravity.CENTER
                background = fondoPulsable(Paleta.PANEL, actividad.dp(20f).toFloat())
                setOnClickListener { ocultar() }
                contentDescription = "Cerrar"
            },
            LinearLayout.LayoutParams(actividad.dp(38f), actividad.dp(38f)),
        )
        fondo.addView(cabecera)

        val desplazable = ScrollView(actividad).apply {
            setPadding(actividad.dp(14f), 0, actividad.dp(14f), actividad.dp(14f))
        }
        val columna = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        columna.addView(
            TextView(actividad).estilo(13f, Paleta.TENUE).apply {
                text = "Rama genera sus respuestas con un modelo que corre acá adentro, sin " +
                    "pasar por la IA de nadie.\n\nLa descarga la hace el gestor del sistema: " +
                    "podés salir de la app o apagar la pantalla y sigue bajando, con el " +
                    "progreso en la barra de notificaciones.\n\nEstán ordenados del más " +
                    "liviano al más capaz. Si dudás, empezá por uno de 1 GB."
                setPadding(0, 0, 0, actividad.dp(14f))
            }
        )
        columna.addView(tarjetas)
        columna.addView(construirImportar())
        desplazable.addView(columna)
        fondo.addView(desplazable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return fondo
    }

    private fun construirImportar(): View {
        val tarjeta = tarjetaVacia()
        tarjeta.addView(
            TextView(actividad).estilo(15f, Paleta.TEXTO, negrita = true).apply {
                text = "Si la descarga no anda"
            }
        )
        tarjeta.addView(
            TextView(actividad).estilo(13f, Paleta.TENUE).apply {
                text = "Copiá el enlace de cualquier modelo con «copiar enlace», pegalo en el " +
                    "navegador y bajalo desde ahí. Después volvé y elegí el archivo con el " +
                    "botón de abajo. Tiene que ser un .gguf."
                setPadding(0, actividad.dp(4f), 0, actividad.dp(10f))
            }
        )
        tarjeta.addView(boton("Elegir un .gguf del teléfono", Paleta.PANEL_ALTO, Paleta.TEXTO) { alImportar() })
        return tarjeta
    }

    // --------------------------------------------------------- contenido

    fun refrescar() {
        tarjetas.removeAllViews()

        if (!Llama.disponible) {
            tarjetas.addView(
                TextView(actividad).estilo(13.5f, 0xFFFF8A80.toInt()).apply {
                    text = "No pude cargar el motor nativo, así que no puedo generar texto:\n" +
                        "${Llama.motivoNoDisponible}\n\nRama sigue funcionando con su base."
                    setPadding(actividad.dp(14f), actividad.dp(12f), actividad.dp(14f), actividad.dp(12f))
                    background = fondoRedondeado(
                        Paleta.PANEL, actividad.dp(14f).toFloat(), Paleta.BORDE, actividad.dp(1f),
                    )
                }
            )
        }
        for (modelo in Catalogo.MODELOS) tarjetas.addView(tarjetaDe(modelo))
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
        background = fondoRedondeado(Paleta.PANEL, actividad.dp(16f).toFloat(), Paleta.BORDE, actividad.dp(1f))
        setPadding(actividad.dp(16f), actividad.dp(14f), actividad.dp(16f), actividad.dp(14f))
    }

    private fun tarjetaDe(modelo: ModeloDisponible): View {
        val archivo = descargas.archivoDe(modelo)
        val estado = descargas.estado(modelo)
        val enUso = modeloActivo()?.absolutePath == archivo.absolutePath

        val tarjeta = tarjetaVacia()
        tarjeta.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = actividad.dp(12f) }

        val encabezado = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        encabezado.addView(
            TextView(actividad).estilo(16f, Paleta.TEXTO, negrita = true).apply { text = modelo.nombre },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (enUso) {
            encabezado.addView(
                TextView(actividad).estilo(11.5f, Paleta.ACENTO_OSCURO, negrita = true).apply {
                    text = "EN USO"
                    padding(actividad.dp(9f), actividad.dp(4f))
                    background = fondoRedondeado(Paleta.ACENTO, actividad.dp(10f).toFloat())
                }
            )
        }
        tarjeta.addView(encabezado)

        tarjeta.addView(
            TextView(actividad).estilo(12f, Paleta.TENUE, monoespaciada = true).apply {
                val peso = if (modelo.bytesAproximados >= 1024L * 1024 * 1024) {
                    "%.1f GB".format(modelo.bytesAproximados / 1024.0 / 1024 / 1024)
                } else {
                    "%.0f MB".format(modelo.bytesAproximados / 1024.0 / 1024)
                }
                text = "${modelo.familia} · $peso · necesita ~${modelo.ramRecomendada} de RAM"
                setPadding(0, actividad.dp(3f), 0, 0)
            }
        )
        tarjeta.addView(
            TextView(actividad).estilo(12f, Paleta.ACENTO, monoespaciada = true).apply {
                text = "precisión ${modelo.barra(modelo.precision)}   velocidad ${modelo.barra(modelo.velocidad)}"
                setPadding(0, actividad.dp(5f), 0, 0)
            }
        )
        tarjeta.addView(
            TextView(actividad).estilo(13f, Paleta.TENUE).apply {
                text = modelo.descripcion
                setPadding(0, actividad.dp(8f), 0, actividad.dp(10f))
            }
        )

        when (estado) {
            is EstadoDescarga.EnCurso -> {
                tarjeta.addView(
                    ProgressBar(actividad, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = (estado.fraccion * 1000).toInt()
                        isIndeterminate = estado.totales <= 0
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actividad.dp(6f)),
                )
                tarjeta.addView(
                    TextView(actividad).estilo(12f, Paleta.TENUE).apply {
                        val hechos = estado.bytes / 1024.0 / 1024
                        val totales = estado.totales / 1024.0 / 1024
                        text = when {
                            estado.enPausa -> "en pausa · esperando conexión"
                            estado.totales > 0 -> "%.0f de %.0f MB (%.0f%%)".format(
                                hechos, totales, estado.fraccion * 100,
                            )
                            else -> "%.0f MB descargados".format(hechos)
                        }
                        setPadding(0, actividad.dp(6f), 0, actividad.dp(8f))
                    }
                )
                tarjeta.addView(
                    filaDeBotones(
                        boton("Cancelar", Paleta.PANEL_ALTO, Paleta.TENUE) {
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
                            boton("Quitar de la memoria", Paleta.PANEL_ALTO, Paleta.TENUE) {
                                alBorrar(archivo)
                                refrescar()
                            }
                        )
                    )
                } else {
                    tarjeta.addView(
                        filaDeBotones(
                            boton("Usar este", Paleta.ACENTO, Paleta.ACENTO_OSCURO) {
                                alUsar(estado.archivo)
                                ocultar()
                            },
                            boton("Borrar", Paleta.PANEL_ALTO, Paleta.TENUE) {
                                estado.archivo.delete()
                                refrescar()
                            },
                        )
                    )
                }
            }

            is EstadoDescarga.Fallo -> {
                tarjeta.addView(
                    TextView(actividad).estilo(12.5f, 0xFFFF8A80.toInt()).apply {
                        text = "La descarga falló: ${estado.motivo}"
                        setPadding(0, 0, 0, actividad.dp(8f))
                    }
                )
                tarjeta.addView(
                    filaDeBotones(
                        boton("Reintentar", Paleta.ACENTO, Paleta.ACENTO_OSCURO) { descargar(modelo) },
                        boton("Copiar enlace", Paleta.PANEL_ALTO, Paleta.TENUE) { copiarEnlace(modelo) },
                    )
                )
            }

            EstadoDescarga.Ninguna -> tarjeta.addView(
                filaDeBotones(
                    boton("Descargar", Paleta.ACENTO, Paleta.ACENTO_OSCURO) { descargar(modelo) },
                    boton("Copiar enlace", Paleta.PANEL_ALTO, Paleta.TENUE) { copiarEnlace(modelo) },
                )
            )
        }
        return tarjeta
    }

    // -------------------------------------------------------- acciones

    private fun descargar(modelo: ModeloDisponible) {
        val aviso = TextView(actividad).estilo(12.5f, Paleta.TENUE).apply {
            text = "buscando el archivo…"
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
            TextView(actividad).estilo(12.5f, 0xFFFF8A80.toInt()).apply {
                text = "No encontré «${modelo.nombre}» en ninguno de sus repositorios:\n\n" +
                    intentos.joinToString("\n") +
                    "\n\nProbá con otro modelo, o copiá el enlace y bajalo desde el navegador."
                setPadding(actividad.dp(14f), actividad.dp(12f), actividad.dp(14f), actividad.dp(12f))
                background = fondoRedondeado(
                    Paleta.PANEL, actividad.dp(14f).toFloat(), Paleta.BORDE, actividad.dp(1f),
                )
            },
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
            botones.forEach { addView(it) }
        }

    private fun boton(texto: String, fondo: Int, color: Int, alTocar: () -> Unit): TextView =
        TextView(actividad).estilo(14f, color, negrita = true).apply {
            this.text = texto
            gravity = Gravity.CENTER
            padding(actividad.dp(16f), actividad.dp(10f))
            background = fondoPulsable(fondo, actividad.dp(12f).toFloat(), Paleta.BORDE, actividad.dp(1f))
            setOnClickListener { alTocar() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = actividad.dp(8f) }
        }
}
