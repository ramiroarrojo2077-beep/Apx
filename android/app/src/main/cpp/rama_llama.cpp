// Puente JNI entre Rama y llama.cpp.
//
// Todo lo que sigue corre dentro del teléfono: carga un modelo GGUF, arma el
// prompt con la plantilla de chat del propio modelo y genera token por token,
// entregándolos a Kotlin a medida que salen.

#include <jni.h>

#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

namespace {

/** Un modelo cargado y listo para generar. */
struct Sesion {
    llama_model *        modelo       = nullptr;
    llama_context *      ctx          = nullptr;
    const llama_vocab *  vocab        = nullptr;
    llama_sampler *      muestreador  = nullptr;
    std::mutex           candado;
    bool                 cancelar     = false;
};

Sesion * sesion_de(jlong handle) {
    return reinterpret_cast<Sesion *>(handle);
}

/**
 * Devuelve cuántos bytes del principio forman UTF-8 completo.
 *
 * Un token puede cortar una letra al medio: "ñ" y "á" ocupan dos bytes. Si
 * entregáramos el fragmento suelto, Java lo convertiría en basura, así que
 * guardamos la cola incompleta hasta que llegue el resto.
 */
size_t prefijo_utf8_completo(const std::string & bytes) {
    size_t i = 0;
    while (i < bytes.size()) {
        const unsigned char c = static_cast<unsigned char>(bytes[i]);
        size_t largo;
        if (c < 0x80)             largo = 1;
        else if ((c >> 5) == 0x6) largo = 2;
        else if ((c >> 4) == 0xE) largo = 3;
        else if ((c >> 3) == 0x1E) largo = 4;
        else                      largo = 1;  // byte inválido: lo dejamos pasar

        if (i + largo > bytes.size()) return i;  // secuencia cortada al final
        i += largo;
    }
    return i;
}

std::string texto_del_token(const llama_vocab * vocab, llama_token token) {
    char buffer[256];
    int32_t n = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, false);
    if (n < 0) {
        std::vector<char> grande(-n);
        n = llama_token_to_piece(vocab, token, grande.data(), static_cast<int32_t>(grande.size()), 0, false);
        return n > 0 ? std::string(grande.data(), n) : std::string();
    }
    return std::string(buffer, n);
}

std::vector<llama_token> tokenizar(const llama_vocab * vocab, const std::string & texto, bool especiales) {
    const int32_t maximo = static_cast<int32_t>(texto.size()) + 16;
    std::vector<llama_token> tokens(maximo);
    const int32_t n = llama_tokenize(vocab, texto.c_str(), static_cast<int32_t>(texto.size()),
                                     tokens.data(), maximo, especiales, true);
    if (n < 0) {
        tokens.resize(-n);
        llama_tokenize(vocab, texto.c_str(), static_cast<int32_t>(texto.size()),
                       tokens.data(), -n, especiales, true);
    } else {
        tokens.resize(n);
    }
    return tokens;
}

std::string desde_jstring(JNIEnv * env, jstring texto) {
    if (texto == nullptr) return {};
    const char * crudo = env->GetStringUTFChars(texto, nullptr);
    std::string copia = crudo ? crudo : "";
    if (crudo) env->ReleaseStringUTFChars(texto, crudo);
    return copia;
}

/** Silencia los logs de llama.cpp: en un teléfono no los ve nadie. */
void sin_logs(ggml_log_level, const char *, void *) {}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_ar_rama_ai_motor_Llama_nativeIniciar(JNIEnv *, jobject) {
    llama_log_set(sin_logs, nullptr);
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_ar_rama_ai_motor_Llama_nativeAbrir(JNIEnv * env, jobject, jstring ruta, jint n_ctx, jint n_hilos) {
    const std::string archivo = desde_jstring(env, ruta);

    llama_model_params parametros_modelo = llama_model_default_params();
    parametros_modelo.n_gpu_layers = 0;  // CPU: es lo único garantizado en Android

    llama_model * modelo = llama_model_load_from_file(archivo.c_str(), parametros_modelo);
    if (modelo == nullptr) return 0;

    llama_context_params parametros_ctx = llama_context_default_params();
    parametros_ctx.n_ctx         = static_cast<uint32_t>(n_ctx);
    parametros_ctx.n_batch       = 512;
    parametros_ctx.n_threads     = n_hilos;
    parametros_ctx.n_threads_batch = n_hilos;

    llama_context * ctx = llama_init_from_model(modelo, parametros_ctx);
    if (ctx == nullptr) {
        llama_model_free(modelo);
        return 0;
    }

    Sesion * sesion = new Sesion();
    sesion->modelo = modelo;
    sesion->ctx    = ctx;
    sesion->vocab  = llama_model_get_vocab(modelo);
    return reinterpret_cast<jlong>(sesion);
}

JNIEXPORT void JNICALL
Java_ar_rama_ai_motor_Llama_nativeCerrar(JNIEnv *, jobject, jlong handle) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return;
    if (sesion->muestreador) llama_sampler_free(sesion->muestreador);
    if (sesion->ctx)         llama_free(sesion->ctx);
    if (sesion->modelo)      llama_model_free(sesion->modelo);
    delete sesion;
}

JNIEXPORT jstring JNICALL
Java_ar_rama_ai_motor_Llama_nativeInfo(JNIEnv * env, jobject, jlong handle) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return env->NewStringUTF("");

    char descripcion[256] = {0};
    llama_model_desc(sesion->modelo, descripcion, sizeof(descripcion));

    const uint64_t parametros = llama_model_n_params(sesion->modelo);
    const uint32_t contexto   = llama_n_ctx(sesion->ctx);

    std::string info = std::string(descripcion) +
                       " · " + std::to_string(parametros / 1000000) + "M parámetros" +
                       " · contexto " + std::to_string(contexto);
    return env->NewStringUTF(info.c_str());
}

/**
 * Arma el prompt con la plantilla de chat que trae el propio modelo.
 * Devuelve vacío si el GGUF no incluye plantilla: ahí Kotlin usa la suya.
 */
JNIEXPORT jstring JNICALL
Java_ar_rama_ai_motor_Llama_nativeFormatearChat(JNIEnv * env, jobject, jlong handle,
                                                jobjectArray roles, jobjectArray contenidos,
                                                jboolean agregar_asistente) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return env->NewStringUTF("");

    const char * plantilla = llama_model_chat_template(sesion->modelo, nullptr);
    if (plantilla == nullptr) return env->NewStringUTF("");

    const jsize cantidad = env->GetArrayLength(roles);
    std::vector<std::string> textos_rol(cantidad);
    std::vector<std::string> textos_contenido(cantidad);
    std::vector<llama_chat_message> mensajes(cantidad);

    for (jsize i = 0; i < cantidad; i++) {
        jstring rol = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        jstring contenido = static_cast<jstring>(env->GetObjectArrayElement(contenidos, i));
        textos_rol[i]       = desde_jstring(env, rol);
        textos_contenido[i] = desde_jstring(env, contenido);
        env->DeleteLocalRef(rol);
        env->DeleteLocalRef(contenido);
        mensajes[i].role    = textos_rol[i].c_str();
        mensajes[i].content = textos_contenido[i].c_str();
    }

    std::vector<char> buffer(8192);
    int32_t n = llama_chat_apply_template(plantilla, mensajes.data(), mensajes.size(),
                                          agregar_asistente == JNI_TRUE,
                                          buffer.data(), static_cast<int32_t>(buffer.size()));
    if (n > static_cast<int32_t>(buffer.size())) {
        buffer.resize(n);
        n = llama_chat_apply_template(plantilla, mensajes.data(), mensajes.size(),
                                      agregar_asistente == JNI_TRUE,
                                      buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (n < 0) return env->NewStringUTF("");
    return env->NewStringUTF(std::string(buffer.data(), n).c_str());
}

JNIEXPORT void JNICALL
Java_ar_rama_ai_motor_Llama_nativeCancelar(JNIEnv *, jobject, jlong handle) {
    Sesion * sesion = sesion_de(handle);
    if (sesion != nullptr) sesion->cancelar = true;
}

/**
 * Genera la respuesta token por token.
 *
 * Cada fragmento se entrega a `receptor.onToken(String)`; si ese método
 * devuelve false, la generación se corta. Devuelve cuántos tokens produjo,
 * o un número negativo si algo falló.
 */
JNIEXPORT jint JNICALL
Java_ar_rama_ai_motor_Llama_nativeGenerar(JNIEnv * env, jobject, jlong handle, jstring prompt,
                                          jint max_tokens, jfloat temperatura, jfloat top_p,
                                          jint top_k, jlong semilla, jobject receptor) {
    Sesion * sesion = sesion_de(handle);
    if (sesion == nullptr) return -1;

    std::lock_guard<std::mutex> bloqueo(sesion->candado);
    sesion->cancelar = false;

    jclass clase = env->GetObjectClass(receptor);
    jmethodID on_token = env->GetMethodID(clase, "onToken", "(Ljava/lang/String;)Z");
    if (on_token == nullptr) return -2;

    // Cada respuesta arranca con el contexto limpio: es más simple de razonar
    // que arrastrar la caché, y el historial ya viaja dentro del prompt.
    llama_memory_clear(llama_get_memory(sesion->ctx), true);

    if (sesion->muestreador) llama_sampler_free(sesion->muestreador);
    sesion->muestreador = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_temp(temperatura));
    llama_sampler_chain_add(sesion->muestreador, llama_sampler_init_dist(static_cast<uint32_t>(semilla)));

    const std::string texto = desde_jstring(env, prompt);
    std::vector<llama_token> tokens = tokenizar(sesion->vocab, texto, true);
    if (tokens.empty()) return -3;

    const uint32_t contexto = llama_n_ctx(sesion->ctx);
    if (tokens.size() + 8 >= contexto) {
        // El prompt no entra: recortamos por el principio, que es lo más viejo.
        tokens.erase(tokens.begin(), tokens.begin() + (tokens.size() + 8 - contexto));
    }

    llama_batch lote = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(sesion->ctx, lote) != 0) return -4;

    std::string pendiente;   // bytes UTF-8 que todavía no forman una letra
    int generados = 0;

    for (int i = 0; i < max_tokens; i++) {
        if (sesion->cancelar) break;

        const llama_token token = llama_sampler_sample(sesion->muestreador, sesion->ctx, -1);
        if (llama_vocab_is_eog(sesion->vocab, token)) break;

        pendiente += texto_del_token(sesion->vocab, token);
        const size_t completo = prefijo_utf8_completo(pendiente);
        if (completo > 0) {
            jstring fragmento = env->NewStringUTF(pendiente.substr(0, completo).c_str());
            const jboolean seguir = env->CallBooleanMethod(receptor, on_token, fragmento);
            env->DeleteLocalRef(fragmento);
            if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            pendiente.erase(0, completo);
            if (seguir != JNI_TRUE) break;
        }

        generados++;
        llama_token siguiente = token;
        llama_batch lote_uno = llama_batch_get_one(&siguiente, 1);
        if (llama_decode(sesion->ctx, lote_uno) != 0) break;
    }

    return generados;
}

}  // extern "C"
