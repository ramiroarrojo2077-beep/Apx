"""Mide qué tan bien generaliza Rama sobre frases que no están en su base.

Uso: python3 -m rama.evaluar [--detalle]
"""

import argparse
import json
import tempfile
from pathlib import Path

from .cerebro import UMBRAL_BAJO, Rama
from .memoria import Memoria

ARCHIVO_EVALUACION = Path(__file__).resolve().parent.parent / "data" / "evaluacion.json"


def evaluar(ia: Rama, casos: list[dict], detalle: bool = False) -> dict:
    aciertos, errores, dudosos = 0, [], 0
    for caso in casos:
        ranking = ia.clasificar(caso["texto"])
        predicha, confianza = (ranking[0][0], ranking[0][1]) if ranking else ("desconocida", 0.0)
        if predicha == caso["intencion"]:
            aciertos += 1
            if confianza < UMBRAL_BAJO:
                dudosos += 1  # acertó, pero por debajo del umbral: contestaría "no sé"
        else:
            errores.append((caso["texto"], caso["intencion"], predicha, round(confianza, 3)))
        if detalle:
            marca = "ok  " if predicha == caso["intencion"] else "FALLA"
            print(f"  {marca} {caso['texto'][:45]:<47} -> {predicha} ({confianza:.3f})")

    total = len(casos) or 1
    return {
        "total": len(casos),
        "aciertos": aciertos,
        "precision": aciertos / total,
        "bajo_umbral": dudosos,
        "errores": errores,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="rama.evaluar", description="Evalúa el clasificador de Rama")
    parser.add_argument("--casos", default=str(ARCHIVO_EVALUACION))
    parser.add_argument("--detalle", action="store_true", help="imprime caso por caso")
    args = parser.parse_args(argv)

    casos = json.loads(Path(args.casos).read_text(encoding="utf-8"))["casos"]
    # Memoria descartable en /tmp: la evaluación mide la base, no lo aprendido.
    with tempfile.TemporaryDirectory() as temporal:
        ia = Rama(memoria=Memoria(archivo=Path(temporal) / "memoria.json"))
        resultado = evaluar(ia, casos, args.detalle)

    print(f"\nPrecisión: {resultado['aciertos']}/{resultado['total']} "
          f"({resultado['precision']:.1%})")
    if resultado["bajo_umbral"]:
        print(f"Acertó pero por debajo del umbral de confianza: {resultado['bajo_umbral']}")
    if resultado["errores"]:
        print("\nErrores:")
        for texto, esperada, predicha, confianza in resultado["errores"]:
            print(f"  «{texto}»\n     esperaba {esperada} · dijo {predicha} ({confianza})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
