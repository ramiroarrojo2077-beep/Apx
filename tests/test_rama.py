"""Tests de Rama AI. Se corren con: python3 -m unittest discover -s tests"""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from rama.cerebro import UMBRAL_BAJO, Rama  # noqa: E402
from rama.correccion import Corrector, distancia_edicion  # noqa: E402
from rama.memoria import Memoria  # noqa: E402
from rama.servidor import crear_servidor  # noqa: E402
from rama.skills import ErrorCalculo, evaluar_expresion  # noqa: E402
from rama.texto import normalizar, raiz, tokenizar  # noqa: E402
from rama.vectorizador import Vectorizador, similitud  # noqa: E402


class TestTexto(unittest.TestCase):
    def test_normalizar_quita_tildes_y_signos(self):
        self.assertEqual(normalizar("¿Cómo estás?"), "como estas")
        self.assertEqual(normalizar("¡Hola,   Rama!"), "hola rama")

    def test_normalizar_conserva_la_eñe(self):
        self.assertEqual(normalizar("Añejo NIÑO"), "añejo niño")

    def test_raiz_agrupa_variantes(self):
        self.assertEqual(raiz("programando"), raiz("programar"))
        self.assertEqual(raiz("computadoras"), raiz("computadora"))

    def test_raiz_respeta_palabras_cortas(self):
        self.assertEqual(raiz("sol"), "sol")
        self.assertEqual(raiz("casa"), "casa")

    def test_tokenizar_quita_vacias(self):
        self.assertNotIn("de", tokenizar("la capital de francia"))

    def test_tokenizar_no_vacia_frases_de_puras_stopwords(self):
        self.assertTrue(tokenizar("como estas"))


class TestCorreccion(unittest.TestCase):
    def test_transposicion_cuesta_una_edicion(self):
        self.assertEqual(distancia_edicion("pyhton", "python"), 1)

    def test_corte_temprano(self):
        self.assertGreater(distancia_edicion("abc", "xyzw", tope=1), 1)

    def test_corrige_contra_el_vocabulario(self):
        c = Corrector().entrenar(["que es python", "contame un chiste"])
        self.assertEqual(c.corregir("que es pyhton"), "que es python")

    def test_no_toca_palabras_lejanas(self):
        c = Corrector().entrenar(["que es python"])
        self.assertIn("zapallo", c.corregir("zapallo"))


class TestVectorizador(unittest.TestCase):
    def setUp(self):
        self.docs = ["hola como estas", "que es python", "contame un chiste"]
        self.v = Vectorizador().entrenar(self.docs)

    def test_vector_normalizado(self):
        norma = sum(p * p for p in self.v.vectorizar("que es python").values())
        self.assertAlmostEqual(norma, 1.0, places=6)

    def test_identico_da_uno(self):
        vec = self.v.vectorizar("que es python")
        self.assertAlmostEqual(similitud(vec, vec), 1.0, places=6)

    def test_texto_vacio(self):
        self.assertEqual(self.v.vectorizar("   "), {})
        self.assertEqual(similitud({}, self.v.vectorizar("hola")), 0.0)

    def test_el_mas_parecido_gana(self):
        q = self.v.vectorizar("contame un chiste porfa")
        puntajes = {d: similitud(q, self.v.vectorizar(d)) for d in self.docs}
        self.assertEqual(max(puntajes, key=puntajes.get), "contame un chiste")


class TestCalculadora(unittest.TestCase):
    def test_operaciones(self):
        self.assertEqual(evaluar_expresion("2+2"), 4)
        self.assertEqual(evaluar_expresion("(3+5)*2"), 16)
        self.assertEqual(evaluar_expresion("2**10"), 1024)

    def test_division_por_cero(self):
        with self.assertRaises(ErrorCalculo):
            evaluar_expresion("1/0")

    def test_rechaza_codigo_arbitrario(self):
        for peligroso in ("__import__('os').system('ls')", "open('/etc/passwd')", "[1,2]"):
            with self.assertRaises(ErrorCalculo):
                evaluar_expresion(peligroso)

    def test_rechaza_exponente_gigante(self):
        with self.assertRaises(ErrorCalculo):
            evaluar_expresion("9**999999")


class TestMemoria(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.archivo = Path(self.dir.name) / "aprendido.json"
        self.addCleanup(self.dir.cleanup)

    def test_persiste_entre_instancias(self):
        m = Memoria(self.archivo)
        m.aprender("capital de francia", "París")
        m.recordar("nombre", "Ramiro")
        otra = Memoria(self.archivo)
        self.assertEqual(otra.aprendido[0]["respuesta"], "París")
        self.assertEqual(otra.recuerdo("nombre"), "Ramiro")

    def test_aprender_actualiza_sin_duplicar(self):
        m = Memoria(self.archivo)
        m.aprender("color", "verde")
        m.aprender("Color", "azul")
        self.assertEqual(len(m.aprendido), 1)
        self.assertEqual(m.aprendido[0]["respuesta"], "azul")

    def test_olvidar(self):
        m = Memoria(self.archivo)
        m.aprender("x", "y")
        self.assertTrue(m.olvidar("x"))
        self.assertFalse(m.olvidar("x"))

    def test_archivo_corrupto_no_rompe(self):
        self.archivo.write_text("{esto no es json", encoding="utf-8")
        self.assertEqual(Memoria(self.archivo).aprendido, [])


class TestRama(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.ia = Rama(memoria=Memoria(Path(self.dir.name) / "m.json"), semilla=7)

    def test_intenciones_basicas(self):
        casos = {
            "hola": "saludo",
            "chau": "despedida",
            "quien sos": "identidad",
            "que es python": "python",
            "contame un chiste": "chiste",
            "muchas gracias": "gracias",
        }
        for entrada, intencion in casos.items():
            with self.subTest(entrada=entrada):
                self.assertEqual(self.ia.responder(entrada).intencion, intencion)

    def test_tolera_erratas(self):
        self.assertEqual(self.ia.responder("que es pyhton").intencion, "python")

    def test_admite_no_saber(self):
        r = self.ia.responder("cual es el pib de mongolia en 1997")
        self.assertEqual(r.fuente, "fallback")
        self.assertLess(r.confianza, UMBRAL_BAJO)

    def test_aprende_y_recupera(self):
        self.ia.responder("aprende: capital de francia = París")
        r = self.ia.responder("cual es la capital de francia")
        self.assertEqual(r.texto, "París")
        self.assertEqual(r.fuente, "aprendido")

    def test_aprende_por_correccion(self):
        self.ia.responder("cual es mi comida favorita")
        self.ia.responder("responde: milanesas")
        self.assertEqual(self.ia.responder("cual es mi comida favorita").texto, "milanesas")

    def test_olvida(self):
        self.ia.responder("aprende: x = y")
        self.ia.responder("olvidá x")
        self.assertEqual(self.ia.responder("x").fuente, "fallback")

    def test_recuerda_el_nombre(self):
        self.ia.responder("me llamo Ramiro")
        self.assertIn("Ramiro", self.ia.responder("como me llamo").texto)

    def test_skills_ganan_al_conocimiento(self):
        r = self.ia.responder("cuanto es 12*7")
        self.assertEqual(r.fuente, "skill")
        self.assertIn("84", r.texto)

    def test_entrada_vacia(self):
        self.assertEqual(self.ia.responder("   ").intencion, "vacio")

    def test_no_repite_la_misma_respuesta_seguida(self):
        vistas = {self.ia.responder("hola").texto for _ in range(6)}
        self.assertGreater(len(vistas), 1)

    def test_conocimiento_inexistente_no_explota(self):
        vacia = Rama(conocimiento="/no/existe.json",
                     memoria=Memoria(Path(self.dir.name) / "v.json"))
        self.assertEqual(vacia.responder("hola").fuente, "fallback")

    def test_conocimiento_invalido_avisa(self):
        malo = Path(self.dir.name) / "malo.json"
        malo.write_text("{roto", encoding="utf-8")
        with self.assertRaises(ValueError):
            Rama(conocimiento=malo, memoria=Memoria(Path(self.dir.name) / "n.json"))


class TestServidor(unittest.TestCase):
    def setUp(self):
        import threading

        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        ia = Rama(memoria=Memoria(Path(self.dir.name) / "s.json"))
        self.servidor = crear_servidor("127.0.0.1", 0, ia)
        self.puerto = self.servidor.server_address[1]
        hilo = threading.Thread(target=self.servidor.serve_forever, daemon=True)
        hilo.start()
        self.addCleanup(self.servidor.server_close)
        self.addCleanup(self.servidor.shutdown)

    def _post(self, ruta, datos):
        import urllib.error
        import urllib.request

        peticion = urllib.request.Request(
            f"http://127.0.0.1:{self.puerto}{ruta}",
            data=json.dumps(datos).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(peticion, timeout=5) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    def test_chat(self):
        codigo, datos = self._post("/api/chat", {"mensaje": "hola"})
        self.assertEqual(codigo, 200)
        self.assertEqual(datos["intencion"], "saludo")

    def test_peticion_invalida(self):
        codigo, datos = self._post("/api/chat", {"sin_mensaje": 1})
        self.assertEqual(codigo, 400)
        self.assertIn("error", datos)

    def test_estado(self):
        import urllib.request

        with urllib.request.urlopen(f"http://127.0.0.1:{self.puerto}/api/estado", timeout=5) as r:
            datos = json.loads(r.read())
        self.assertGreater(datos["intenciones"], 0)

    def test_ruta_desconocida(self):
        import urllib.error
        import urllib.request

        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(f"http://127.0.0.1:{self.puerto}/otra", timeout=5)
        self.assertEqual(ctx.exception.code, 404)


if __name__ == "__main__":
    unittest.main(verbosity=2)
