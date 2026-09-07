# Rama AI

Una mini inteligencia artificial conversacional en español, escrita **en Python puro**:
sin dependencias, sin API keys, sin internet. Todo el "cerebro" son ~1.100 líneas legibles
que podés abrir y entender.

```
$ python3 -m rama
vos> hola
Rama: ¡Hola! Soy Rama. ¿En qué andás?
vos> cuánto es 12*7
Rama: 12*7 = 84
vos> aprende: mi banda favorita = Soda Stereo
Rama: Listo, aprendí que «mi banda favorita» → «Soda Stereo».
vos> cuál es mi banda favorita?
Rama: Soda Stereo
```

## Uso

Requiere Python 3.10+ y nada más.

```bash
python3 -m rama                      # chat interactivo en la terminal
python3 -m rama "qué es python"      # una pregunta suelta
python3 -m rama.servidor             # chat web en http://127.0.0.1:8000
python3 -m rama.evaluar --detalle    # mide la precisión del clasificador
python3 -m unittest discover -s tests -q   # tests
```

### Comandos de la terminal

| Comando | Qué hace |
|---|---|
| `/ayuda` | lista de comandos |
| `/debug` | muestra fuente, intención, confianza y candidatos de cada respuesta |
| `/aprendido` | todo lo que le enseñaste |
| `/olvidar X` | borra lo aprendido sobre X |
| `/stats` | tamaño de la base de conocimiento |
| `/salir` | terminar |

### Qué entiende

- **Charla**: saludos, despedidas, cómo funciona, quién la hizo, estados de ánimo.
- **Cuentas**: `12*7`, `(3+5)*2`, `2 mas 2`, `el 15% de 200`, `raíz de 144`.
- **Fecha y hora**: `¿qué hora es?`, `¿qué día es hoy?`.
- **Azar**: `tirá una moneda`, `tirá un dado de 20`, `elegí entre pizza o empanadas`.
- **Memoria**: `me llamo Ramiro` → después `¿cómo me llamo?`.
- **Aprendizaje**: `aprende: pregunta = respuesta`, o `responde: ...` para corregir
  la última respuesta. Se guarda en `data/aprendido.json` y sobrevive al reinicio.

## Cómo funciona

```
texto del usuario
      │
      ├─► skills deterministas ──────────► respuesta exacta
      │   (cuentas, fecha, azar, memoria, aprendizaje)
      │
      └─► recuperación por similitud
          normalizar → corregir erratas → TF-IDF → coseno vs. cada patrón
                                                        │
                        confianza ≥ 0.42 ──────────────► responde
                        0.20 ≤ confianza < 0.42 ───────► responde, pero avisa que duda
                        confianza < 0.20 ─────────────► "no sé" + ofrece aprender
```

**No hay red neuronal.** Rama representa cada frase como un vector disperso TF-IDF que
mezcla tres tipos de rasgos:

1. **raíces de palabra** (un stemmer del español recorta sufijos, así "programar",
   "programando" y "programas" caen en el mismo cubo);
2. **bigramas de palabra**, para distinguir "no puedo" de "puedo";
3. **trigramas de letra**, con menos peso, como red de seguridad ante erratas.

Antes de vectorizar, un corrector por **distancia Damerau-Levenshtein** acerca las
palabras desconocidas al vocabulario conocido: la transposición cuenta como una sola
edición, así que `pyhton` → `python`. Después compara por **similitud coseno** contra
todos los patrones y se queda con la mejor intención.

Lo importante está en la última banda: **por debajo del umbral, Rama dice que no sabe**
en vez de inventar. Un modelo chico que alucina es peor que uno que se calla.

## Estructura

```
rama/
  texto.py          normalización, stopwords, stemmer del español
  correccion.py     Damerau-Levenshtein + corrector contra el vocabulario
  vectorizador.py   TF-IDF y similitud coseno, a mano
  skills.py         habilidades deterministas (calculadora AST, fecha, azar, memoria)
  memoria.py        persistencia atómica de lo aprendido y del perfil
  cerebro.py        orquesta todo y decide con qué confianza responder
  cli.py            chat de terminal
  servidor.py       API HTTP + interfaz web (sólo stdlib)
  evaluar.py        mide precisión contra un set de frases nunca vistas
data/
  conocimiento.json base de intenciones (editala y Rama aprende al reiniciar)
  evaluacion.json   set de evaluación
web/index.html      interfaz de chat
tests/test_rama.py  38 tests
```

## Enseñarle cosas

Dos formas:

- **En caliente**, desde el chat: `aprende: cuándo es el asado = el sábado`.
  Se guarda en disco y el índice se reconstruye al instante.
- **En frío**, editando `data/conocimiento.json`: agregá una intención con sus
  `patrones` (varias formas de preguntar) y sus `respuestas` (Rama rota entre ellas
  para no sonar a loop).

```json
{
  "id": "mate",
  "patrones": ["cómo se ceba un mate", "cebame un mate", "el mate va con azúcar"],
  "respuestas": ["Agua a 80°C, nunca hirviendo. Y el azúcar es decisión personal."]
}
```

## Límites (a propósito)

Rama no genera texto nuevo: elige entre respuestas escritas. No sabe nada que no esté en
su base o que no le hayas enseñado, no accede a internet y no tiene modelo de mundo.
Es una IA clásica —simbólica y estadística—, chica, explicable y auditable línea por línea.
