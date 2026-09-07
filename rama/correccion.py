"""Corrector ortográfico mínimo contra el vocabulario que Rama conoce.

No es un diccionario del español: sólo arregla palabras que *casi* coinciden
con algo que Rama ya vio, para que una errata no tire abajo la similitud.
"""

from collections.abc import Iterable

from .texto import normalizar

LARGO_MINIMO = 4


def distancia_edicion(a: str, b: str, tope: int = 2) -> int:
    """Damerau-Levenshtein (con transposición) y corte temprano.

    Contar la transposición como una sola edición importa: "pyhton" es el
    error de tipeo más común y queda a distancia 1 de "python".
    Devuelve `tope + 1` en cuanto se sabe que la distancia lo supera.
    """
    if abs(len(a) - len(b)) > tope:
        return tope + 1
    if a == b:
        return 0

    anterior: list[int] = []
    previa = list(range(len(b) + 1))
    for i, ca in enumerate(a, start=1):
        actual = [i]
        for j, cb in enumerate(b, start=1):
            costo = min(
                previa[j] + 1,               # borrado
                actual[j - 1] + 1,           # inserción
                previa[j - 1] + (ca != cb),  # sustitución
            )
            if i > 1 and j > 1 and ca == b[j - 2] and a[i - 2] == cb:
                costo = min(costo, anterior[j - 2] + 1)  # transposición
            actual.append(costo)
        if min(actual) > tope:
            return tope + 1
        anterior, previa = previa, actual
    return previa[-1]


class Corrector:
    """Mapea palabras desconocidas a la más parecida del vocabulario."""

    def __init__(self) -> None:
        self.vocabulario: set[str] = set()
        self._cache: dict[str, str] = {}

    def entrenar(self, documentos: Iterable[str]) -> "Corrector":
        self.vocabulario = {
            palabra
            for doc in documentos
            for palabra in normalizar(doc).split()
            if len(palabra) >= LARGO_MINIMO
        }
        self._cache.clear()
        return self

    def corregir_palabra(self, palabra: str) -> str:
        if len(palabra) < LARGO_MINIMO or palabra in self.vocabulario:
            return palabra
        if palabra in self._cache:
            return self._cache[palabra]

        tope = 1 if len(palabra) < 7 else 2
        mejor, mejor_distancia = palabra, tope + 1
        for candidata in self.vocabulario:
            distancia = distancia_edicion(palabra, candidata, tope)
            if distancia < mejor_distancia:
                mejor, mejor_distancia = candidata, distancia
                if distancia == 1:
                    break
        self._cache[palabra] = mejor
        return mejor

    def corregir(self, texto: str) -> str:
        if not self.vocabulario:
            return texto
        return " ".join(self.corregir_palabra(p) for p in normalizar(texto).split())
