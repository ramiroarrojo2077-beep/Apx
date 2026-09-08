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
import ar.rama.ai.motor.ResultadoDescarga
import java.io.File
import java.util.concurrent.Executors

/**
 * El panel donde elegís qué modelo usa Rama.
 *
 * Los pesos no vienen dentro del APK: son cientos de megas y no todos quieren
 * el mismo compromiso entre tamaño y calidad. Se bajan una vez y quedan en el
 * teléfono.
 */
class PantallaModelos(
    private val actividad: Activity,
    private val carpeta: File,
    private val modeloActivo: () -> File?,
    private val alUsar: (File) -> Unit,
    private val alBorrar: (File) -> Unit,
    private val alImportar: () -> Unit,
) {

    private val trabajador = Executors.newSingleThreadExecutor { tarea ->
        Thread(tarea, "rama-descarga").apply { isDaemon = true }
    }
    private val principal = Handler(Looper.getMainLooper())
    private val tarjetas = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
    private var descargando: Descargador? = null

    val vista: View = construir()

    fun mostrar() {
        refrescar()
        vista.visibility = View.VISIBLE
    }

    fun ocultar() {
        vista.visibility = View.GONE
    }

    val visible: Boolean get() = vista.visibility == View.VISIBLE

    private fun construir(): View {
        val fondo = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Paleta.FONDO)
            visibility = View.GONE
            isClickable = true  // que no se toque lo que está debajo
        }

        val cabecera = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(actividad.dp(16f), actividad.dp(14f), actividad.dp(12f), actividad.dp(12f))
        }
        val titulo = TextView(actividad).estilo(19f, Paleta.TEXTO, negrita = true).apply {
            text = "Modelo de lenguaje"
        }
        val cerrar = TextView(actividad).estilo(20f, Paleta.TENUE, negrita = true).apply {
            text = "✕"
            gravity = Gravity.CENTER
            background = fondoPulsable(Paleta.PANEL, actividad.dp(20f).toFloat())
            setOnClickListener { ocultar() }
            contentDescription = "Cerrar"
        }
        cabecera.addView(titulo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cabecera.addView(cerrar, LinearLayout.LayoutParams(actividad.dp(38f), actividad.dp(38f)))
        fondo.addView(cabecera)

        val desplazable = ScrollView(actividad).apply {
            setPadding(actividad.dp(14f), 0, actividad.dp(14f), actividad.dp(14f))
        }
        val columna = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }

        columna.addView(
            TextView(actividad).estilo(13f, Paleta.TENUE).apply {
                text = "Rama genera sus respuestas con un modelo que corre acá adentro, " +
                    "sin pasar por la IA de nadie. Los pesos se bajan una vez y quedan en " +
                    "el teléfono. Cuanto más grande, mejor escribe y más lento va."
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
                text = "Importar un .gguf"
            }
        )
        tarjeta.addView(
            TextView(actividad).estilo(13f, Paleta.TENUE).apply {
                text = "Si ya tenés un modelo bajado, o querés usar otro distinto, " +
                    "elegilo desde el teléfono. Tiene que ser formato GGUF."
                setPadding(0, actividad.dp(4f), 0, actividad.dp(10f))
            }
        )
        tarjeta.addView(boton("Elegir archivo", Paleta.PANEL_ALTO, Paleta.TEXTO) { alImportar() })
        return tarjeta
    }

    fun refrescar() {
        tarjetas.removeAllViews()

        if (!Llama.disponible) {
            tarjetas.addView(
                TextView(actividad).estilo(13.5f, 0xFFFF8A80.toInt()).apply {
                    text = "No pude cargar el motor nativo, así que no puedo generar texto:\n" +
                        "${Llama.motivoNoDisponible}\n\n" +
                        "Rama sigue funcionando con su base y sus habilidades."
                    setPadding(actividad.dp(14f), actividad.dp(12f), actividad.dp(14f), actividad.dp(12f))
                    background = fondoRedondeado(Paleta.PANEL, actividad.dp(14f).toFloat(), Paleta.BORDE, actividad.dp(1f))
                }
            )
        }

        for (modelo in Catalogo.MODELOS) {
            tarjetas.addView(tarjetaDe(modelo))
        }
    }

    private fun tarjetaVacia(): LinearLayout {
        val tarjeta = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoRedondeado(Paleta.PANEL, actividad.dp(16f).toFloat(), Paleta.BORDE, actividad.dp(1f))
            setPadding(actividad.dp(16f), actividad.dp(14f), actividad.dp(16f), actividad.dp(14f))
        }
        tarjetas.let { }
        return tarjeta
    }

    private fun tarjetaDe(modelo: ModeloDisponible): View {
        val archivo = File(carpeta, Catalogo.nombreLocal(modelo))
        val descargado = archivo.exists() && Descargador.esGguf(archivo)
        val enUso = descargado && modeloActivo()?.absolutePath == archivo.absolutePath

        val tarjeta = tarjetaVacia()
        val parametros = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = actividad.dp(12f) }
        tarjeta.layoutParams = parametros

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
                val peso = "%.0f MB".format(modelo.bytesAproximados / 1024.0 / 1024.0)
                text = "$peso · necesita ~${modelo.ramRecomendada} de RAM"
                setPadding(0, actividad.dp(3f), 0, 0)
            }
        )
        tarjeta.addView(
            TextView(actividad).estilo(13f, Paleta.TENUE).apply {
                text = modelo.descripcion
                setPadding(0, actividad.dp(8f), 0, actividad.dp(12f))
            }
        )

        val barra = ProgressBar(actividad, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        val estado = TextView(actividad).estilo(12f, Paleta.TENUE).apply { visibility = View.GONE }
        tarjeta.addView(barra, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, actividad.dp(6f)))
        tarjeta.addView(estado)

        val acciones = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, actividad.dp(6f), 0, 0)
        }

        when {
            enUso -> acciones.addView(boton("Quitar de la memoria", Paleta.PANEL_ALTO, Paleta.TENUE) {
                alBorrar(archivo)
                refrescar()
            })

            descargado -> {
                acciones.addView(boton("Usar este", Paleta.ACENTO, Paleta.ACENTO_OSCURO) {
                    alUsar(archivo)
                    ocultar()
                })
                acciones.addView(boton("Borrar", Paleta.PANEL_ALTO, Paleta.TENUE) {
                    archivo.delete()
                    refrescar()
                })
            }

            else -> acciones.addView(
                boton("Descargar", Paleta.ACENTO, Paleta.ACENTO_OSCURO) { boton ->
                    iniciarDescarga(modelo, archivo, barra, estado, boton)
                }
            )
        }
        tarjeta.addView(acciones)
        return tarjeta
    }

    private fun iniciarDescarga(
        modelo: ModeloDisponible,
        archivo: File,
        barra: ProgressBar,
        estado: TextView,
        botonDescargar: TextView,
    ) {
        carpeta.mkdirs()
        val descargador = Descargador(archivo)
        descargando = descargador

        barra.visibility = View.VISIBLE
        estado.visibility = View.VISIBLE
        estado.text = "conectando…"
        botonDescargar.text = "Cancelar"
        botonDescargar.setOnClickListener {
            descargador.cancelar()
            estado.text = "cancelando…"
        }

        trabajador.execute {
            val resultado = descargador.descargar(modelo) { progreso ->
                principal.post {
                    barra.progress = (progreso.fraccion * 1000).toInt()
                    val hechos = progreso.bytesRecibidos / 1024.0 / 1024.0
                    val totales = progreso.bytesTotales / 1024.0 / 1024.0
                    estado.text = "%.0f de %.0f MB (%.0f%%)".format(hechos, totales, progreso.fraccion * 100)
                }
            }
            principal.post {
                descargando = null
                when (resultado) {
                    is ResultadoDescarga.Listo -> {
                        estado.text = "listo"
                        alUsar(resultado.archivo)
                        refrescar()
                    }
                    is ResultadoDescarga.Fallo -> {
                        barra.visibility = View.GONE
                        estado.setTextColor(0xFFFF8A80.toInt())
                        estado.text = resultado.motivo
                        botonDescargar.text = "Reintentar"
                        botonDescargar.setOnClickListener {
                            iniciarDescarga(modelo, archivo, barra, estado, botonDescargar)
                        }
                    }
                    ResultadoDescarga.Cancelada -> refrescar()
                }
            }
        }
    }

    private fun boton(texto: String, fondo: Int, color: Int, alTocar: (TextView) -> Unit): TextView {
        val vista = TextView(actividad).estilo(14f, color, negrita = true).apply {
            this.text = texto
            gravity = Gravity.CENTER
            padding(actividad.dp(16f), actividad.dp(10f))
            background = fondoPulsable(fondo, actividad.dp(12f).toFloat(), Paleta.BORDE, actividad.dp(1f))
        }
        vista.setOnClickListener { alTocar(vista) }
        vista.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = actividad.dp(8f) }
        return vista
    }
}
