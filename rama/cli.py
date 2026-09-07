"""Interfaz de consola de Rama AI."""

import argparse
import sys

from . import __version__
from .cerebro import Rama
from .memoria import Memoria

VERDE = "\033[92m"
CIAN = "\033[96m"
GRIS = "\033[90m"
NEGRITA = "\033[1m"
FIN = "\033[0m"

BANNER = r"""
  ____                          _    ___
 |  _ \ __ _ _ __ ___   __ _   / \  |_ _|
 | |_) / _` | '_ ` _ \ / _` | / _ \  | |
 |  _ < (_| | | | | | | (_| |/ ___ \ | |
 |_| \_\__,_|_| |_| |_|\__,_/_/   \_\___|
"""

AYUDA = """Comandos:
  /ayuda        esta ayuda
  /debug        muestra u oculta la confianza y los candidatos de cada respuesta
  /aprendido    lista lo que te aprendí
  /olvidar X    borra lo aprendido sobre X
  /stats        tamaño de mi base de conocimiento
  /salir        terminar

Ejemplos:
  cuánto es 12*7          ·  qué hora es
  tirá un dado de 20      ·  me llamo Ramiro
  aprende: mi banda favorita = Soda Stereo
"""


def sin_color(activo: bool):
    return (lambda t, _c: t) if not activo else (lambda t, c: f"{c}{t}{FIN}")


def ejecutar_comando(linea: str, ia: Rama, estado: dict, pintar) -> bool:
    """Procesa un comando `/...`. Devuelve False si hay que salir."""
    partes = linea.split(maxsplit=1)
    comando = partes[0].lower()
    argumento = partes[1].strip() if len(partes) > 1 else ""

    if comando in ("/salir", "/exit", "/quit", "/chau"):
        print(pintar("Rama: ¡Hasta la próxima!", VERDE))
        return False
    if comando == "/ayuda":
        print(AYUDA)
    elif comando == "/debug":
        estado["debug"] = not estado["debug"]
        print(pintar(f"debug {'activado' if estado['debug'] else 'desactivado'}", GRIS))
    elif comando == "/aprendido":
        if not ia.memoria.aprendido:
            print(pintar("Todavía no me enseñaste nada.", GRIS))
        for hecho in ia.memoria.aprendido:
            print(f"  · {hecho['pregunta']} → {hecho['respuesta']}")
    elif comando == "/olvidar":
        if not argumento:
            print(pintar("Uso: /olvidar <pregunta>", GRIS))
        elif ia.memoria.olvidar(argumento):
            ia.reindexar()
            print(pintar(f"Olvidado: {argumento}", GRIS))
        else:
            print(pintar("No tenía eso guardado.", GRIS))
    elif comando == "/stats":
        print(pintar(
            f"  intenciones: {ia.total_intenciones}\n"
            f"  patrones indexados: {len(ia.entradas)}\n"
            f"  rasgos en vocabulario: {len(ia.vectorizador.idf)}\n"
            f"  aprendido de vos: {len(ia.memoria.aprendido)}",
            GRIS,
        ))
    else:
        print(pintar(f"No conozco el comando {comando}. Probá /ayuda.", GRIS))
    return True


def repl(ia: Rama, color: bool = True) -> int:
    pintar = sin_color(color)
    estado = {"debug": False}
    print(pintar(BANNER, CIAN))
    print(pintar(f"  Rama AI v{__version__} · mini IA en Python puro · /ayuda para empezar\n", GRIS))

    while True:
        try:
            linea = input(pintar("vos> ", NEGRITA)).strip()
        except (EOFError, KeyboardInterrupt):
            print()
            print(pintar("Rama: ¡Chau!", VERDE))
            return 0

        if not linea:
            continue
        if linea.startswith("/"):
            if not ejecutar_comando(linea, ia, estado, pintar):
                return 0
            continue

        respuesta = ia.responder(linea)
        print(pintar(f"Rama: {respuesta.texto}", VERDE))
        if estado["debug"]:
            candidatos = ", ".join(f"{i}={p}" for i, p in respuesta.candidatos) or "-"
            print(pintar(
                f"      [fuente={respuesta.fuente} intencion={respuesta.intencion} "
                f"confianza={respuesta.confianza} candidatos: {candidatos}]",
                GRIS,
            ))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="rama", description="Rama AI: una mini inteligencia artificial en español."
    )
    parser.add_argument("pregunta", nargs="*", help="pregunta suelta; sin esto abre el chat interactivo")
    parser.add_argument("--conocimiento", help="ruta a un JSON de conocimiento alternativo")
    parser.add_argument("--memoria", help="ruta al archivo de memoria persistente")
    parser.add_argument("--sin-color", action="store_true", help="salida sin códigos ANSI")
    parser.add_argument("--version", action="version", version=f"Rama AI {__version__}")
    args = parser.parse_args(argv)

    ia = Rama(conocimiento=args.conocimiento, memoria=Memoria(archivo=args.memoria) if args.memoria else None)

    if args.pregunta:
        print(ia.responder(" ".join(args.pregunta)).texto)
        return 0
    return repl(ia, color=not args.sin_color and sys.stdout.isatty())


if __name__ == "__main__":
    raise SystemExit(main())
