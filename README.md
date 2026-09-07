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
rama/                 la versión de escritorio (Python)
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

android/              la app (Kotlin, sin dependencias)
  app/src/main/java/ar/rama/ai/
    motor/            el mismo cerebro portado: Texto, Correccion,
                      Vectorizador, Calculadora, Memoria, Skills, Cerebro
    MainActivity.kt   el chat, el modo pensar y los adjuntos
    AnalizadorAdjuntos.kt  fotos, PDFs y videos
    Ui.kt             paleta y drawables generados en código
  app/src/test/       38 tests del motor, en la JVM
```

## En el teléfono: la app Android

`android/` es una app nativa de Android con el mismo motor portado a Kotlin,
**sin AndroidX, sin Material, sin una sola librería de terceros**: sólo el
framework. (Robolectric, que corre la interfaz en los tests, sí depende de
androidx.test, pero eso vive únicamente en el classpath de test: el APK no
lleva una sola clase de androidx.) El APK pesa 876 KB y no pide ningún permiso — los
archivos llegan por el selector del sistema (SAF), así que Rama sólo ve lo que
vos le pasás.

### Bajar el APK

Cada push construye el APK en GitHub Actions y lo publica acá:

**https://github.com/ramiroarrojo2077-beep/Apx/releases/download/apk/rama-ai.apk**

Está firmado con la clave *debug* de Android: para instalarlo hay que permitir
"instalar apps de orígenes desconocidos" en el teléfono. Es un build de
desarrollo, no una publicación de Play Store.

### Construirlo vos

```bash
cd android
./gradlew testDebugUnitTest   # 38 tests del motor, en la JVM
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Requiere JDK 17 y el SDK de Android (`compileSdk 34`). `minSdk 26`, o sea
Android 8.0 en adelante.

### Modo pensar

El botón 🧠 del encabezado despliega, antes de cada respuesta, el razonamiento
real del motor paso a paso:

```
NORMALIZACIÓN        «¿Qué es Pyhton?» → «que es pyhton»
                     tokens: pyhton
CORRECCIÓN           «que es pyhton» → «que es python»
HABILIDADES          ninguna de las 6 habilidades aplicó
VECTORIZACIÓN        41 rasgos: 1 raíz, 0 bigramas, 40 trigramas
SIMILITUD COSENO     0.544  python
                     0.061  que es tfidf
DECISIÓN             confianza 0.544 ≥ 0.42 → respondo directo
```

No es una animación decorativa: son los pasos que el motor ejecutó, con los
números que realmente usó para decidir. Se despliegan con un ritmo de ~230 ms
para que se puedan leer, y quedan plegados bajo la respuesta.

### Fotos, PDFs y videos

Con **＋** (o compartiéndole un archivo desde otra app) Rama analiza:

| Tipo | Qué extrae |
|---|---|
| **Foto** | dimensiones, megapíxeles, proporción, color medio y luminosidad reales (promedia los píxeles), y el EXIF: cámara, fecha, apertura, exposición, ISO |
| **PDF** | cantidad de páginas, tamaño de hoja (A4, Carta, A3…), orientación y una miniatura de la portada renderizada con `PdfRenderer` |
| **Video** | duración, resolución y etiqueta de calidad (HD/Full HD/4K), rotación, bitrate, cuadros por segundo y un fotograma del 10% |

Después podés preguntarle "¿cuántas páginas tenía el PDF?" y responde con lo
que analizó.

**El límite, dicho de frente:** Rama lee el archivo, no lo entiende. Sabe que
tu foto es vertical, de 12 MP y predominantemente azul; no sabe que hay un
perro en ella. Del PDF cuenta las páginas y dibuja la portada, pero no extrae
el texto. Para eso haría falta un modelo de visión, y eso no entra en un APK
de 876 KB que funciona sin internet.


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
