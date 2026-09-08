package ar.rama.ai

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
        }

        val cabecera = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(actividad.dp(16f), actividad.dp(14f), actividad.dp(12f), actividad.dp(12f))
        }
        cabecera.addView(
            TextView(actividad).estilo(19f, Paleta.TEXTO, negrita = true).apply { text = "Chats" },
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
            TextView(actividad).estilo(15f, Paleta.ACENTO_OSCURO, negrita = true).apply {
                text = "＋  Chat nuevo"
                gravity = Gravity.CENTER
                padding(actividad.dp(16f), actividad.dp(13f))
                background = fondoPulsable(Paleta.ACENTO, actividad.dp(14f).toFloat())
                setOnClickListener {
                    alNuevo()
                    ocultar()
                }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = actividad.dp(16f) }
            }
        )
        columna.addView(lista)
        desplazable.addView(columna)
        fondo.addView(desplazable, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return fondo
    }

    fun refrescar() {
        lista.removeAllViews()
        val guardados = conversaciones.listar()

        if (guardados.isEmpty()) {
            lista.addView(
                TextView(actividad).estilo(13.5f, Paleta.TENUE).apply {
                    text = "Todavía no hay chats guardados.\n\nCada conversación se guarda sola apenas " +
                        "escribís algo, y queda acá hasta que la borres."
                    gravity = Gravity.CENTER
                    setPadding(actividad.dp(20f), actividad.dp(40f), actividad.dp(20f), 0)
                }
            )
            return
        }

        for (resumen in guardados) lista.addView(fila(resumen))

        lista.addView(
            TextView(actividad).estilo(13f, 0xFFFF8A80.toInt()).apply {
                text = "Borrar todos los chats"
                gravity = Gravity.CENTER
                padding(actividad.dp(14f), actividad.dp(12f))
                background = fondoPulsable(Paleta.PANEL, actividad.dp(12f).toFloat(), Paleta.BORDE, actividad.dp(1f))
                setOnClickListener { confirmarBorrarTodo(this) }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = actividad.dp(20f) }
            }
        )
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
    }

    private fun fila(resumen: ResumenConversacion): View {
        val esActual = resumen.id == chatActual()
        val fila = LinearLayout(actividad).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = fondoPulsable(
                if (esActual) Paleta.PANEL_ALTO else Paleta.PANEL,
                actividad.dp(14f).toFloat(),
                if (esActual) Paleta.ACENTO else Paleta.BORDE,
                actividad.dp(1f),
            )
            setPadding(actividad.dp(14f), actividad.dp(12f), actividad.dp(8f), actividad.dp(12f))
            setOnClickListener {
                alAbrir(resumen.id)
                ocultar()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = actividad.dp(8f) }
        }

        val textos = LinearLayout(actividad).apply { orientation = LinearLayout.VERTICAL }
        textos.addView(
            TextView(actividad).estilo(14.5f, Paleta.TEXTO).apply {
                text = resumen.titulo
                maxLines = 2
            }
        )
        textos.addView(
            TextView(actividad).estilo(11.5f, Paleta.TENUE).apply {
                val cuantos = resumen.cantidadMensajes
                text = "${cuandoFue(resumen.actualizada)} · $cuantos mensaje${if (cuantos == 1) "" else "s"}"
                setPadding(0, actividad.dp(3f), 0, 0)
            }
        )
        fila.addView(textos, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val borrar = TextView(actividad).estilo(15f, Paleta.TENUE).apply {
            text = "🗑"
            gravity = Gravity.CENTER
            background = fondoPulsable(Paleta.PANEL_ALTO, actividad.dp(16f).toFloat())
            contentDescription = "Borrar «${resumen.titulo}»"
        }
        borrar.setOnClickListener {
            if (borrar.tag == "confirmando") {
                conversaciones.borrar(resumen.id)
                if (resumen.id == chatActual()) alNuevo()
                refrescar()
            } else {
                borrar.tag = "confirmando"
                borrar.text = "✓"
                borrar.setTextColor(0xFFFF8A80.toInt())
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
