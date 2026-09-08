"""El cerebro de Rama: skills deterministas + recuperación por similitud."""

import json
import random
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .enciclopedia import Enciclopedia
from .memoria import Memoria
from .skills import COMANDOS, SKILLS
from .texto import normalizar, tokenizar
from .vectorizador import Vectorizador, similitud

ARCHIVO_CONOCIMIENTO = Path(__file__).resolve().parent.parent / "data" / "conocimiento.json"

# Bandas de confianza. Debajo de la baja, Rama admite que no sabe.
UMBRAL_ALTO = 0.42
UMBRAL_BAJO = 0.20
UMBRAL_PISTA = 0.09
UMBRAL_SUGERENCIA = 0.16

SIN_IDEA = (
    "No sé responder eso todavía.",
    "Eso se me escapa.",
    "No tengo nada parecido en mi base.",
)


@dataclass
class Paso:
    """Un paso del razonamiento, para el modo pensar."""

    titulo: str
    detalle: str


@dataclass
class Respuesta:
    """Lo que Rama contesta, con la trazabilidad de cómo lo decidió."""

    texto: str
    intencion: str = "desconocida"
    confianza: float = 0.0
    fuente: str = "fallback"
    candidatos: list[tuple[str, float]] = field(default_factory=list)
    pasos: list[Paso] = field(default_factory=list)

    def __str__(self) -> str:
        return self.texto


@dataclass
class _Entrada:
    """Un patrón indexado y a qué intención pertenece."""

    patron: str
    intencion: str
    fuente: str
    vector: dict[str, float] = field(default_factory=dict)


class Rama:
    """Mini IA conversacional en español.

    >>> ia = Rama(memoria=Memoria(archivo="/tmp/rama-demo.json"))
    >>> ia.responder("hola").intencion
    'saludo'
    """

    def __init__(
        self,
        conocimiento: Path | str | None = None,
        memoria: Memoria | None = None,
        semilla: int | None = None,
    ) -> None:
        self.ruta_conocimiento = Path(conocimiento) if conocimiento else ARCHIVO_CONOCIMIENTO
        self.memoria = memoria if memoria is not None else Memoria()
        self.azar = random.Random(semilla)
        self.intenciones: dict[str, list[str]] = {}
        self.entradas: list[_Entrada] = []
        self.vectorizador = Vectorizador()
        self.enciclopedia = Enciclopedia()
        self.capitales: dict[str, tuple[str, str]] = {}
        self.alias: dict[str, str] = {}
        self._ultima_respuesta: dict[str, int] = {}
        self.reindexar()

    # ------------------------------------------------------------- índice

    @property
    def total_intenciones(self) -> int:
        return len(self.intenciones)

    def reindexar(self) -> None:
        """(Re)construye la base y reentrena el vectorizador.

        Se llama al arrancar y cada vez que Rama aprende u olvida algo.
        """
        self.intenciones = {}
        self.entradas = []

        datos = self._leer_conocimiento()
        self.capitales, self.alias = self._indexar_paises(datos.get("paises", {}))
        for intencion in datos.get("intenciones", []):
            ident = intencion["id"]
            self.intenciones[ident] = list(intencion.get("respuestas", []))
            for patron in intencion.get("patrones", []):
                self.entradas.append(_Entrada(patron, ident, "conocimiento"))

        # Lo aprendido en caliente: cada hecho es su propia intención.
        for i, hecho in enumerate(self.memoria.aprendido):
            ident = f"aprendido:{i}"
            self.intenciones[ident] = [hecho["respuesta"]]
            self.entradas.append(_Entrada(hecho["pregunta"], ident, "aprendido"))

        self.vectorizador.entrenar(e.patron for e in self.entradas)
        for entrada in self.entradas:
            entrada.vector = self.vectorizador.vectorizar(entrada.patron)

    @staticmethod
    def _indexar_paises(paises: dict[str, Any]) -> tuple[dict, dict]:
        """Prepara la tabla de capitales para buscar sin tildes ni mayúsculas."""
        capitales = {
            normalizar(pais): (pais.title(), capital)
            for pais, capital in paises.get("capitales", {}).items()
        }
        alias = {normalizar(k): normalizar(v) for k, v in paises.get("alias", {}).items()}
        return capitales, alias

    def _leer_conocimiento(self) -> dict[str, Any]:
        try:
            return json.loads(self.ruta_conocimiento.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {"intenciones": []}
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"El archivo de conocimiento {self.ruta_conocimiento} no es JSON válido: {exc}"
            ) from exc

    # ---------------------------------------------------------- inferencia

    def clasificar(self, texto: str) -> list[tuple[str, float, str]]:
        """Devuelve (intención, confianza, fuente) ordenado de mejor a peor."""
        consulta = self.vectorizador.vectorizar(texto, corregir=True)
        if not consulta:
            return []

        mejor: dict[str, tuple[float, str]] = {}
        for entrada in self.entradas:
            puntaje = similitud(consulta, entrada.vector)
            previo = mejor.get(entrada.intencion)
            if previo is None or puntaje > previo[0]:
                mejor[entrada.intencion] = (puntaje, entrada.fuente)

        ranking = [(i, p, f) for i, (p, f) in mejor.items() if p > 0]
        ranking.sort(key=lambda x: x[1], reverse=True)
        return ranking

    def _mejor_aprendido(self, texto: str) -> tuple[str, float] | None:
        """La mejor coincidencia entre lo que el usuario le enseñó, y nada más."""
        aprendidas = [e for e in self.entradas if e.fuente == "aprendido"]
        if not aprendidas:
            return None
        consulta = self.vectorizador.vectorizar(texto, corregir=True)
        if not consulta:
            return None
        mejor = max(aprendidas, key=lambda e: similitud(consulta, e.vector))
        return mejor.intencion, similitud(consulta, mejor.vector)

    def _elegir_respuesta(self, intencion: str) -> str:
        """Rota entre las respuestas de una intención para no sonar a loop."""
        opciones = self.intenciones.get(intencion) or ["..."]
        if len(opciones) == 1:
            return opciones[0]
        anterior = self._ultima_respuesta.get(intencion)
        indices = [i for i in range(len(opciones)) if i != anterior]
        elegido = self.azar.choice(indices)
        self._ultima_respuesta[intencion] = elegido
        return opciones[elegido]

    def responder(self, texto: str) -> Respuesta:
        """Punto de entrada único: texto del usuario -> respuesta de Rama."""
        texto = (texto or "").strip()
        if not texto:
            return Respuesta("Decime algo y te contesto.", "vacio", 0.0, "guardia")

        self.memoria.registrar_turno("usuario", texto)
        pasos: list[Paso] = []

        plano = normalizar(texto)
        tokens = tokenizar(texto)
        pasos.append(Paso("Normalización",
                          f"«{texto}»\n→ «{plano}»\ntokens: {', '.join(tokens)}"))

        corregido = self.vectorizador.corrector.corregir(texto)
        pasos.append(Paso(
            "Corrección de erratas",
            "sin cambios: todas las palabras me suenan conocidas" if corregido == plano
            else f"«{plano}»\n→ «{corregido}»  (Damerau-Levenshtein contra mi vocabulario)",
        ))

        for comando in COMANDOS:
            salida = comando(texto, self)
            if salida:
                pasos.append(Paso("Comando", "reconocí una orden directa (aprender, olvidar o corregir)"))
                self.memoria.registrar_turno("rama", salida)
                return Respuesta(salida, comando.__name__, 1.0, "comando", pasos=pasos)

        # Lo que te enseñó el usuario manda sobre cualquier habilidad: es la
        # forma que tiene de corregirla, y no serviría de nada si perdiera.
        ensenado = self._mejor_aprendido(texto)
        if ensenado and ensenado[1] >= UMBRAL_ALTO:
            respuesta = self._elegir_respuesta(ensenado[0])
            pasos.append(Paso("Memoria",
                              f"esto me lo enseñaste vos (parecido {ensenado[1]:.3f}), "
                              "así que va antes que mis habilidades"))
            self.memoria.ultima_pregunta_sin_respuesta = None
            self.memoria.registrar_turno("rama", respuesta)
            return Respuesta(respuesta, ensenado[0], round(ensenado[1], 4), "aprendido", pasos=pasos)

        for skill in SKILLS:
            salida = skill(texto, self)
            if salida:
                nombre = skill.__name__.replace("skill_", "").replace("_", " ")
                pasos.append(Paso("Habilidades",
                                  f"coincidió la habilidad «{nombre}»: respuesta exacta, "
                                  "sin buscar por similitud"))
                pasos.append(Paso("Decisión",
                                  "una habilidad determinista resuelve la consulta, "
                                  "así que no hay incertidumbre"))
                self.memoria.registrar_turno("rama", salida)
                return Respuesta(salida, skill.__name__, 1.0, "skill", pasos=pasos)

        nombres = ", ".join(s.__name__.replace("skill_", "").replace("_", " ") for s in SKILLS)
        pasos.append(Paso("Habilidades", f"ninguna de las {len(SKILLS)} habilidades aplicó ({nombres})"))

        vector = self.vectorizador.vectorizar(texto, corregir=True)
        familias = Counter(rasgo[0] for rasgo in vector)
        pasos.append(Paso(
            "Vectorización TF-IDF",
            f"{len(vector)} rasgos: {familias['p']} raíces, {familias['b']} bigramas, "
            f"{familias['n']} trigramas de letra\n"
            f"comparo contra {len(self.entradas)} patrones "
            f"(vocabulario de {len(self.vectorizador.idf)} rasgos)",
        ))

        ranking = self.clasificar(texto)
        candidatos = [(i, round(p, 4)) for i, p, _ in ranking[:3]]
        pasos.append(Paso(
            "Similitud coseno",
            "ningún patrón comparte rasgos con tu frase" if not ranking
            else "\n".join(f"{p:.3f}  {i.replace('_', ' ')}" for i, p, _ in ranking[:4]),
        ))

        if ranking and ranking[0][1] >= UMBRAL_BAJO:
            intencion, confianza, fuente = ranking[0]
            respuesta = self._elegir_respuesta(intencion)
            segura = confianza >= UMBRAL_ALTO
            if not segura:
                respuesta = f"No estoy del todo segura, pero creo que va por acá: {respuesta}"
            pasos.append(Paso(
                "Decisión",
                f"confianza {confianza:.3f} ≥ {UMBRAL_ALTO} → respondo directo" if segura
                else f"confianza {confianza:.3f} entre {UMBRAL_BAJO} y {UMBRAL_ALTO} → "
                     "respondo, pero aviso que dudo",
            ))
            self.memoria.ultima_pregunta_sin_respuesta = None
            self.memoria.registrar_turno("rama", respuesta)
            return Respuesta(respuesta, intencion, round(confianza, 4), fuente, candidatos, pasos)

        confianza = ranking[0][1] if ranking else 0.0
        pasos.append(Paso("Decisión",
                          f"confianza {confianza:.3f} < {UMBRAL_BAJO} → "
                          "prefiero decir que no sé antes que inventar"))
        salida = self._sin_respuesta(texto, ranking)
        self.memoria.registrar_turno("rama", salida)
        return Respuesta(salida, "desconocida", round(confianza, 4), "fallback", candidatos, pasos)

    def _sin_respuesta(self, texto: str, ranking: list[tuple[str, float, str]]) -> str:
        """Admite la ignorancia, pero deja al usuario en algún lado."""
        self.memoria.ultima_pregunta_sin_respuesta = texto
        base = self.azar.choice(SIN_IDEA)
        cercanos = [
            i.replace("_", " ") for i, p, _ in ranking[:2]
            if p >= UMBRAL_SUGERENCIA and not i.startswith("aprendido:")
        ]
        if cercanos:
            return (
                f"{base} De lo parecido que sí manejo: {', '.join(cercanos)}. "
                "Si no era por ahí, enseñame la respuesta con «responde: ...» y queda guardada."
            )
        return (
            f"{base} Enseñame escribiendo «responde: la respuesta que esperabas» y no vuelvo a fallar "
            "en esto. También sé de tecnología, ciencia, capitales, cuentas, conversiones y fechas: "
            "probá preguntarme por ahí."
        )
