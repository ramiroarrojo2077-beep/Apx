"""Miles de temas con búsqueda directa, sin costo de indexado.

A diferencia de la base conversacional, que compara por similitud contra cada
patrón, acá se extrae el sujeto de la pregunta y se busca en un diccionario.
Es O(1): entran decenas de miles de temas sin penalizar el arranque.
"""

import json
import re
from pathlib import Path

from .texto import normalizar

ARCHIVO = Path(__file__).resolve().parent.parent / "data" / "datos.json"

PREGUNTAS = [
    re.compile(r"^(?:me podes decir |decime |sabes )?(?:que) (?:es|son|significan?|quiere decir) "
               r"(?:el |la |los |las |un |una |unos |unas )?(.+)$"),
    re.compile(r"^(?:quien) (?:es|fue|era|son|fueron) (?:el |la |los |las )?(.+)$"),
    re.compile(r"^(?:cuando) (?:fue|paso|ocurrio|sucedio|se invento|nacio|murio) (?:el |la |los |las )?(.+)$"),
    re.compile(r"^(?:donde) (?:esta|queda|se encuentra|nacio) (?:el |la |los |las )?(.+)$"),
    re.compile(r"^para (?:que) (?:sirve|sirven|se usa|se usan) (?:el |la |los |las )?(.+)$"),
    re.compile(r"^(?:como) (?:funciona|funcionan|se hace|se forma|se produce) (?:el |la |los |las )?(.+)$"),
    re.compile(r"^(?:hablame|contame|explicame) (?:de|sobre|del|acerca de) (?:el |la |los |las )?(.+)$"),
    re.compile(r"^(?:definicion|significado|concepto) de (?:el |la |los |las )?(.+)$"),
    re.compile(r"^(?:informacion|datos) (?:de|sobre) (?:el |la |los |las )?(.+)$"),
    re.compile(r"^(?:en que) (?:consiste|continente esta|pais esta) (?:el |la |los |las )?(.+)$"),
]


class Enciclopedia:
    """Tabla de temas consultable por el sujeto de la pregunta."""

    def __init__(self, archivo: Path | str | None = None) -> None:
        ruta = Path(archivo) if archivo else ARCHIVO
        try:
            datos = json.loads(ruta.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            datos = {}
        self.entradas = {
            normalizar(clave): texto
            for clave, texto in datos.get("entradas", {}).items()
            if texto
        }
        # Índice por palabra: permite encontrar "albert einstein" preguntando
        # sólo "einstein", que es como pregunta la gente.
        self.por_palabra: dict[str, list[str]] = {}
        for clave in self.entradas:
            for palabra in clave.split():
                if len(palabra) >= 4:
                    self.por_palabra.setdefault(palabra, []).append(clave)

    def __len__(self) -> int:
        return len(self.entradas)

    def buscar(self, pregunta: str) -> str | None:
        sujeto = self.sujeto_de(pregunta)
        return self._por_clave(sujeto) if sujeto else None

    def _por_clave(self, sujeto: str) -> str | None:
        if sujeto in self.entradas:
            return self.entradas[sujeto]

        # "los planetas" -> "planeta"; "raíces" -> "raíz"
        if sujeto.endswith("ces"):
            singular = sujeto[:-3] + "z"
        elif sujeto.endswith("es") and len(sujeto) > 4:
            singular = sujeto[:-2]
        elif sujeto.endswith("s") and len(sujeto) > 3:
            singular = sujeto[:-1]
        else:
            singular = None
        if singular and singular in self.entradas:
            return self.entradas[singular]

        for plural in (sujeto + "s", sujeto + "es"):
            if plural in self.entradas:
                return self.entradas[plural]

        return self._por_palabras(sujeto)

    def _por_palabras(self, sujeto: str) -> str | None:
        """Busca la entrada que contenga todas las palabras del sujeto."""
        palabras = [p for p in sujeto.split() if len(p) >= 4]
        if not palabras:
            return None

        candidatas = set(self.por_palabra.get(palabras[0], []))
        for palabra in palabras[1:]:
            candidatas &= set(self.por_palabra.get(palabra, []))
        if not candidatas:
            return None
        # La más corta es la más específica: "einstein" -> "albert einstein",
        # no "premio nobel de física a albert einstein".
        return self.entradas[min(candidatas, key=len)]

    @staticmethod
    def sujeto_de(pregunta: str) -> str | None:
        """«¿Qué es la fotosíntesis?» -> «fotosintesis»."""
        limpia = normalizar(pregunta)
        if not limpia:
            return None
        for forma in PREGUNTAS:
            encontrado = forma.match(limpia)
            if encontrado:
                sujeto = encontrado.group(1).strip()
                if sujeto:
                    return sujeto
        # Un tema escrito solo, sin forma de pregunta.
        return limpia if len(limpia.split()) <= 4 else None
