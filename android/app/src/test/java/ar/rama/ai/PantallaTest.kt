package ar.rama.ai

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Levanta la pantalla real dentro de la JVM.
 *
 * Sin esto, todo el código de interfaz sólo estaba comprobado por el
 * compilador: cualquier error de ejecución recién aparecía en el teléfono.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PantallaTest {

    private val abiertas = mutableListOf<ActivityController<MainActivity>>()

    @After
    fun cerrarPantallas() {
        // Sin esto, cada test dejaría una Activity viva con su hilo de trabajo.
        abiertas.forEach { runCatching { it.pause().stop().destroy() } }
        abiertas.clear()
    }

    private fun abrirPantalla(): MainActivity {
        val controlador = Robolectric.buildActivity(MainActivity::class.java).setup()
        abiertas.add(controlador)
        return controlador.get()
    }

    private fun raiz(actividad: MainActivity): View =
        actividad.findViewById(android.R.id.content)

    private fun textos(vista: View): List<String> = when (vista) {
        is TextView -> listOf(vista.text.toString())
        is ViewGroup -> (0 until vista.childCount).flatMap { textos(vista.getChildAt(it)) }
        else -> emptyList()
    }

    private fun buscar(vista: View, predicado: (View) -> Boolean): View? {
        if (predicado(vista)) return vista
        if (vista is ViewGroup) {
            for (i in 0 until vista.childCount) {
                buscar(vista.getChildAt(i), predicado)?.let { return it }
            }
        }
        return null
    }

    /** Deja correr el hilo de trabajo y el looper hasta que se cumpla algo. */
    private fun esperar(condicion: () -> Boolean): Boolean {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS)
            if (condicion()) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun escribirYEnviar(actividad: MainActivity, mensaje: String) {
        val campo = buscar(raiz(actividad)) { it is EditText } as EditText
        campo.setText(mensaje)
        val enviar = buscar(raiz(actividad)) { it.contentDescription == "Enviar" }
        assertNotNull("no encontré el botón de enviar", enviar)
        enviar!!.performClick()
    }

    @Test
    fun laPantallaArrancaSinRomperse() {
        val actividad = abrirPantalla()
        assertNotNull("no hay campo de texto", buscar(raiz(actividad)) { it is EditText })
        assertNotNull("no hay botón de enviar", buscar(raiz(actividad)) { it.contentDescription == "Enviar" })
    }

    @Test
    fun cargaElCerebroYSaluda() {
        val actividad = abrirPantalla()
        val saludo = esperar { textos(raiz(actividad)).any { it.contains("Soy Rama") } }
        assertTrue("nunca apareció el saludo. En pantalla: ${textos(raiz(actividad))}", saludo)
        val encabezado = textos(raiz(actividad)).any { it.contains("intenciones") }
        assertTrue("el encabezado no muestra el tamaño de la base", encabezado)
    }

    @Test
    fun respondeUnMensaje() {
        val actividad = abrirPantalla()
        assertTrue(esperar { textos(raiz(actividad)).any { it.contains("Soy Rama") } })

        escribirYEnviar(actividad, "que es python")

        val respondio = esperar {
            textos(raiz(actividad)).any { it.contains("lenguaje interpretado") }
        }
        assertTrue("no contestó. En pantalla: ${textos(raiz(actividad))}", respondio)
    }

    @Test
    fun elModoPensarMuestraLosPasos() {
        val actividad = abrirPantalla()
        assertTrue(esperar { textos(raiz(actividad)).any { it.contains("Soy Rama") } })

        escribirYEnviar(actividad, "que es python")
        assertTrue(esperar { textos(raiz(actividad)).any { it.contains("lenguaje interpretado") } })

        val pantalla = textos(raiz(actividad))
        assertTrue("no se ve la traza del razonamiento: $pantalla",
            pantalla.any { it.contains("pasos de razonamiento") })
    }

    @Test
    fun laCalculadoraResponde() {
        val actividad = abrirPantalla()
        assertTrue(esperar { textos(raiz(actividad)).any { it.contains("Soy Rama") } })

        escribirYEnviar(actividad, "cuanto es 12*7")

        val respondio = esperar { textos(raiz(actividad)).any { it.contains("84") } }
        assertTrue("no resolvió la cuenta. En pantalla: ${textos(raiz(actividad))}", respondio)
    }
}
