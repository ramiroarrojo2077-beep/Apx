package ar.rama.ai

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ar.rama.ai.motor.Conversaciones
import ar.rama.ai.motor.ResumenConversacion
import java.util.Calendar

/**
 * La lista de chats guardados.
 *
 * Todo vive en el almacenamiento privado de la app: ninguna conversación sale
 * del teléfono, y desinstalar se las lleva a todas.
 */
class PantallaChats(
    private val actividad: Activity,
    private val conversaciones: Conversaciones,
    private val chatActual: () -> String,
    private val alAbrir: (String) -> Unit,
    private val alNuevo: () -> Unit,
) {

    private val lista = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
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
            isClickable = true
            fitsSystemWindows = true
        }

        fondo.addView(Hoja.cabecera(actividad, "Chats") { ocultar() })
        fondo.addView(actividad.divisor())

        val desplazable = ScrollView(actividad).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(actividad.dp(Espacio.L), actividad.dp(Espacio.L), actividad.dp(Espacio.L), actividad.dp(Espacio.XL))
        }
        val columna = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }

        columna.addView(botonChatNuevo())
        columna.addView(lista)
        desplazable.addView(columna)
        fondo.addView(desplazable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return fondo
    }

    /** La acción principal de la pantalla, y la única pintada con el acento. */
    private fun botonChatNuevo(): View {
        val boton = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = fondoPulsable(Paleta.ACENTO, actividad.dp(Radio.MEDIO).toFloat())
            padding(actividad.dp(Espacio.L), actividad.dp(Espacio.M + 2f))
            setOnClickListener {
                alNuevo()
                ocultar()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = actividad.dp(Espacio.XL) }
        }
        boton.addView(
            ImageView(actividad).apply {
                setImageDrawable(Icono(Iconos.mas(), Paleta.SOBRE_ACENTO, grosor = 2.2f))
            },
            LinearLayout.LayoutParams(actividad.dp(16f), actividad.dp(16f)).apply {
                rightMargin = actividad.dp(Espacio.S)
            },
        )
        boton.addView(
            TextView(actividad).estilo(Tipo.SECUNDARIO + 0.5f, Paleta.SOBRE_ACENTO, negrita = true, interlineado = 1f)
                .apply { text = "Chat nuevo" }
        )
        return boton
    }

    fun refrescar() {
        lista.removeAllViews()
        val guardados = conversaciones.listar()

        if (guardados.isEmpty()) {
            lista.addView(vacio())
            return
        }

        lista.addView(
            TextView(actividad).apply { text = "Guardados" }.rotulo().apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = actividad.dp(Espacio.M) }
            }
        )
        for (resumen in guardados) lista.addView(fila(resumen))

        lista.addView(
            actividad.boton("Borrar todos los chats", color = Paleta.ERROR, fondo = Paleta.SUPERFICIE) {}
                .apply {
                    setOnClickListener { confirmarBorrarTodo(this) }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = actividad.dp(Espacio.XL) }
                }
        )
    }

    /** Cuando no hay nada, explicar qué va a aparecer acá. */
    private fun vacio(): View {
        val columna = LinearLayout(actividad).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(actividad.dp(Espacio.XL), actividad.dp(Espacio.XXL), actividad.dp(Espacio.XL), 0)
        }
        columna.addView(
            ImageView(actividad).apply {
                setImageDrawable(Icono(Iconos.chats(), Paleta.TEXTO_3))
                alpha = 0.7f
            },
            LinearLayout.LayoutParams(actividad.dp(30f), actividad.dp(30f)),
        )
        columna.addView(
            TextView(actividad).estilo(Tipo.SECUNDARIO, Paleta.TEXTO_2, interlineado = 1.45f).apply {
                text = "Todavía no hay chats guardados.\n\nCada conversación se guarda sola apenas " +
                    "escribís algo, y queda acá hasta que la borres."
                gravity = Gravity.CENTER
                setPadding(0, actividad.dp(Espacio.L), 0, 0)
            }
        )
        return columna
    }

    /** Dos toques para borrar todo: el primero avisa, el segundo ejecuta. */
    private fun confirmarBorrarTodo(boton: TextView) {
        if (boton.tag == "confirmando") {
            conversaciones.borrarTodo()
            alNuevo()
            refrescar()
            return
        }
        boton.tag = "confirmando"
        boton.text = "¿Seguro? Tocá otra vez para borrarlos"
        boton.background = fondoPulsable(
            Paleta.ERROR_TENUE, actividad.dp(Radio.MEDIO).toFloat(), Paleta.ERROR, actividad.dp(1f),
        )
    }

    private fun fila(resumen: ResumenConversacion): View {
        val esActual = resumen.id == chatActual()
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fondoPulsable(
                if (esActual) Paleta.ACENTO_TENUE else Paleta.SUPERFICIE,
                actividad.dp(Radio.MEDIO).toFloat(),
                if (esActual) Paleta.ACENTO else Paleta.BORDE,
                actividad.dp(1f),
            )
            setPadding(
                actividad.dp(Espacio.L - 2f), actividad.dp(Espacio.M + 1f),
                actividad.dp(Espacio.S), actividad.dp(Espacio.M + 1f),
            )
            setOnClickListener {
                alAbrir(resumen.id)
                ocultar()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = actividad.dp(Espacio.S) }
        }

        val textos = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        val titulo = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (esActual) {
            titulo.addView(
                actividad.punto(Paleta.ACENTO, 6f),
                LinearLayout.LayoutParams(actividad.dp(6f), actividad.dp(6f)).apply {
                    rightMargin = actividad.dp(7f)
                },
            )
        }
        titulo.addView(
            TextView(actividad).estilo(Tipo.SECUNDARIO + 1f, Paleta.TEXTO, interlineado = 1.25f).apply {
                text = resumen.titulo
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        )
        textos.addView(titulo)
        textos.addView(
            TextView(actividad).estilo(Tipo.MICRO, Paleta.TEXTO_3, interlineado = 1f).apply {
                val cuantos = resumen.cantidadMensajes
                text = "${cuandoFue(resumen.actualizada)} · $cuantos mensaje${if (cuantos == 1) "" else "s"}"
                setPadding(0, actividad.dp(Espacio.XS + 1f), 0, 0)
            }
        )
        fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val borrar = actividad.botonIcono(
            Iconos.papelera(), "Borrar «${resumen.titulo}»",
            lado = 34f, tamanioIcono = 16f,
            color = Paleta.TEXTO_3, fondo = Color.TRANSPARENT, borde = Color.TRANSPARENT,
        ) {}
        borrar.setOnClickListener {
            if (borrar.tag == "confirmando") {
                conversaciones.borrar(resumen.id)
                if (resumen.id == chatActual()) alNuevo()
                refrescar()
            } else {
                // Un toque arma, el segundo borra. Sin diálogos: en una app de
                // una sola pantalla, un cartel modal es más molesto que útil.
                borrar.tag = "confirmando"
                borrar.setImageDrawable(Icono(Iconos.visto(), Paleta.ERROR, grosor = 2.2f))
                borrar.background = fondoPulsable(Paleta.ERROR_TENUE, actividad.dp(17f).toFloat())
            }
        }
        fila.addView(borrar, LinearLayout.LayoutParams(actividad.dp(34f), actividad.dp(34f)))
        return fila
    }

    companion object {
        /** Fechas como las diría una persona, no como las escribe una máquina. */
        fun cuandoFue(momento: Long, referencia: Long = System.currentTimeMillis()): String {
            val minutos = (referencia - momento) / 60_000
            if (minutos < 1) return "recién"
            if (minutos < 60) return "hace $minutos min"

            val hoy = Calendar.getInstance().apply { timeInMillis = referencia }
            val cuando = Calendar.getInstance().apply { timeInMillis = momento }
            val mismoAnio = hoy.get(Calendar.YEAR) == cuando.get(Calendar.YEAR)
            val diaDelAnio = hoy.get(Calendar.DAY_OF_YEAR) - cuando.get(Calendar.DAY_OF_YEAR)

            if (mismoAnio && diaDelAnio == 0) {
                val hora = cuando.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                val minuto = cuando.get(Calendar.MINUTE).toString().padStart(2, '0')
                return "hoy $hora:$minuto"
            }
            if (mismoAnio && diaDelAnio == 1) return "ayer"
            if (mismoAnio && diaDelAnio in 2..6) return "hace $diaDelAnio días"

            val dia = cuando.get(Calendar.DAY_OF_MONTH)
            val mes = ar.rama.ai.motor.Skills.MESES[cuando.get(Calendar.MONTH)].take(3)
            return if (mismoAnio) "$dia $mes" else "$dia $mes ${cuando.get(Calendar.YEAR)}"
        }
    }
}

/**
 * La cabecera compartida de las pantallas que se abren encima del chat.
 *
 * Que las dos se vean exactamente igual es lo que hace que se sientan parte de
 * la misma app y no dos cosas pegadas.
 */
object Hoja {
    fun cabecera(actividad: Activity, titulo: String, alCerrar: () -> Unit): View {
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                actividad.dp(Espacio.L), actividad.dp(Espacio.M),
                actividad.dp(Espacio.M), actividad.dp(Espacio.M),
            )
        }
        fila.addView(
            TextView(actividad).estilo(Tipo.TITULO, Paleta.TEXTO, negrita = true, interlineado = 1f).apply {
                text = titulo
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        fila.addView(
            actividad.botonIcono(Iconos.cerrar(), "Cerrar", alTocar = alCerrar),
            LinearLayout.LayoutParams(actividad.dp(40f), actividad.dp(40f)),
        )
        return fila
    }
}
