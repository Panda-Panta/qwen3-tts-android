#include <android/log.h>
#include <asm/hwcap.h>
#include <jni.h>
#include <sys/auxv.h>

#include "ggml-backend.h"
#include "llama.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <string>

namespace {

constexpr char log_tag[] = "QwenLlamaTts";
constexpr uint32_t seed = 42;
constexpr uint32_t context_size = 1024;
constexpr uint32_t batch_size = 512;

using Clock = std::chrono::steady_clock;
using Sampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;

long long elapsed_ms(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(Clock::now() - start).count();
}

void log_native(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_DEBUG) {
        return;
    }
    const auto priority = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR :
                          level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
    __android_log_write(priority, log_tag, text);
}

void set_env(const char * key, const char * value) {
    const int status = value ? setenv(key, value, 1) : unsetenv(key);
    if (status != 0) {
        throw std::runtime_error(std::string("Could not configure ") + key);
    }
}

void load_backends(const std::string & directory, bool vulkan) {
    static const bool initialized = [] {
        llama_log_set(log_native, nullptr);
        mtmd_helper_log_set(log_native, nullptr);
        llama_backend_init();
        return true;
    }();
    (void) initialized;

    if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
        ggml_backend_load_all_from_path(directory.c_str());
        if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
            throw std::runtime_error("Could not load the CPU backend.");
        }
    }

    if (vulkan && !ggml_backend_dev_by_name("Vulkan0")) {
        const std::string driver = directory + "/libvulkan_freedreno.so";
        set_env("GGML_TEST_TURNIP_DRIVER", driver.c_str());
        const std::string backend = directory + "/libqwen-ggml-vulkan.so";
        if (!ggml_backend_load(backend.c_str()) || !ggml_backend_dev_by_name("Vulkan0")) {
            throw std::runtime_error("Could not initialize the Vulkan vocoder. Select CPU in Settings.");
        }
    }
}

struct Session {
    bool vulkan;
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
    mtmd::context_ptr audio_model;
    mtmd_helper::gen_audio_ptr generator;

    explicit Session(bool vulkan) : vulkan(vulkan) {}

    const char * backend_name() const {
        return vulkan ? "Vulkan vocoder" : "CPU";
    }
};

std::string get_string(JNIEnv * env, jstring value) {
    if (!value) {
        return {};
    }
    const auto release = [env, value](const char * chars) { env->ReleaseStringUTFChars(value, chars); };
    const std::unique_ptr<const char, decltype(release)> chars(env->GetStringUTFChars(value, nullptr), release);
    if (!chars) {
        throw std::runtime_error("Could not read the request.");
    }
    return chars.get();
}

std::string get_text(JNIEnv * env, jbyteArray value) {
    if (!value || env->GetArrayLength(value) == 0) {
        throw std::invalid_argument("Enter text to speak.");
    }
    const jsize size = env->GetArrayLength(value);
    std::string text(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(text.data()));
    if (env->ExceptionCheck()) {
        throw std::runtime_error("Could not read the text.");
    }
    return text;
}

void throw_java(JNIEnv * env, const char * type, const char * message) {
    if (!env->ExceptionCheck()) {
        if (jclass klass = env->FindClass(type)) {
            env->ThrowNew(klass, message);
        }
    }
}

Sampler make_sampler() {
    Sampler sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    // Match the tested 0.6B Base model's sampling metadata and CLI min-p default.
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(50));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.9f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(seed));
    return sampler;
}

jobject synthesize(JNIEnv * env, Session & session, const std::string & text,
                   const std::string & reference, const std::string & language, int max_frames) {
    const auto start = Clock::now();
    auto * context = session.context.get();
    auto * generator = session.generator.get();
    auto * audio_model = session.audio_model.get();
    auto * vocab = llama_model_get_vocab(session.model.get());

    // Qwen's prompt template adds at most 11 positions around the text.
    const auto text_tokens = llama_tokenize(vocab, text.data(), static_cast<int32_t>(text.size()),
                                            nullptr, 0, false, true);
    if (text_tokens >= 0 || -static_cast<int64_t>(text_tokens) + 11 >= llama_n_ctx_seq(context)) {
        throw std::invalid_argument("Text is too long for one request. Split it into shorter passages.");
    }

    llama_synchronize(context);
    llama_memory_clear(llama_get_memory(context), true);

    mtmd::bitmap_ptr speaker;
    if (!reference.empty()) {
        speaker.reset(mtmd_helper_bitmap_init_from_file(audio_model, reference.c_str(), false,
                                                        mtmd_helper_init_opt_default()).bitmap);
        if (!speaker || !mtmd_bitmap_is_audio(speaker.get())) {
            throw std::invalid_argument("Could not read the reference recording.");
        }
    }

    auto input = mtmd_helper_gen_audio_inp_default();
    input.prompt = text.data();
    input.prompt_len = text.size();
    input.speaker_ref = speaker.get();
    input.lang = language.c_str();
    input.seed = seed;
    input.out_type = MTMD_HELPER_GEN_AUDIO_OUTTYPE_PCM;
    if (mtmd_helper_gen_audio_set_input(generator, &input) != 0) {
        throw std::runtime_error("Could not prepare the text or reference recording.");
    }
    int remaining;
    do {
        remaining = mtmd_helper_gen_audio_step_prompt(generator, batch_size);
        if (remaining < 0) {
            throw std::runtime_error("Could not encode the text.");
        }
    } while (remaining > 0);

    const int64_t context_remaining = static_cast<int64_t>(llama_n_ctx_seq(context)) -
                                      llama_memory_seq_pos_max(llama_get_memory(context), input.seq_id) - 1;
    const int frame_limit = static_cast<int>(std::min<int64_t>(max_frames, context_remaining));
    auto sampler = make_sampler();
    auto sampled = llama_sampler_sample(sampler.get(), context, -1);
    const float * hidden = llama_get_embeddings_ith(context, -1);
    int frames = 0;
    for (; frames < frame_limit; ++frames) {
        const float * next = nullptr;
        bool stop = false;
        if (mtmd_helper_gen_audio_step_gen(generator, sampled, hidden, &next, &stop) != 0) {
            throw std::runtime_error("Speech synthesis failed.");
        }
        if (stop || !next) {
            break;
        }
        hidden = next;
        sampled = llama_sampler_sample(sampler.get(), context, -1);
    }

    if (frames == frame_limit && !llama_vocab_is_eog(vocab, sampled) &&
            std::strcmp(llama_vocab_get_text(vocab, sampled), "<|codec_eos_token|>") != 0) {
        throw std::invalid_argument("The audio exceeded the generation limit. Split the text into shorter passages.");
    }

    int32_t sample_rate = 0;
    const char * data = nullptr;
    size_t bytes = 0;
    int64_t samples = 0;
    if (frames <= 0 || mtmd_helper_gen_audio_get_output(generator, &sample_rate, &data, &bytes, &samples) != 0 ||
            !data || sample_rate <= 0 || samples <= 0 || samples > std::numeric_limits<jsize>::max() ||
            bytes != static_cast<size_t>(samples) * sizeof(float)) {
        throw std::runtime_error("The vocoder did not produce audio.");
    }

    const auto count = static_cast<jsize>(samples);
    jfloatArray pcm = env->NewFloatArray(count);
    if (!pcm) {
        return nullptr;
    }
    env->SetFloatArrayRegion(pcm, 0, count, reinterpret_cast<const float *>(data));
    mtmd_helper_gen_audio_reset(generator);
    if (env->ExceptionCheck()) {
        return nullptr;
    }

    jclass result = env->FindClass("com/qwen/tts/studio/engine/LlamaTtsEngine$Result");
    jmethodID constructor = result ? env->GetMethodID(result, "<init>", "([FII)V") : nullptr;
    if (!constructor) {
        return nullptr;
    }
    __android_log_print(ANDROID_LOG_INFO, log_tag, "SYNTH %s frames=%d rate=%d ms=%lld",
                        session.backend_name(), frames, sample_rate, elapsed_ms(start));
    return env->NewObject(result, constructor, pcm, sample_rate, frames);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_qwen_tts_studio_engine_LlamaTtsEngine_nativeLoad(
        JNIEnv * env, jobject, jstring model, jstring mmproj, jstring native_directory,
        jboolean vulkan, jint threads) {
    try {
        const auto start = Clock::now();
        const auto model_path = get_string(env, model);
        const auto mmproj_path = get_string(env, mmproj);
        const auto directory = get_string(env, native_directory);
        if (model_path.empty() || mmproj_path.empty() || directory.empty() || threads <= 0) {
            throw std::invalid_argument("Model paths and CPU thread count are required.");
        }
        const bool use_vulkan = vulkan == JNI_TRUE;
        load_backends(directory, use_vulkan);
        set_env("MTMD_QWEN3TTS_VOCODER_DEVICE", use_vulkan ? "Vulkan0" : nullptr);
        const bool repack = (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) ||
                            (getauxval(AT_HWCAP2) & HWCAP2_I8MM);
        set_env("MTMD_QWEN3TTS_ARM_INT8", repack ? "layers" : nullptr);
        set_env("MTMD_QWEN3TTS_REUSE_PREDICTOR_GRAPH", "1");
        set_env("MTMD_QWEN3TTS_ASYNC_FRAMES", nullptr);
        set_env("MTMD_QWEN3TTS_VOCODER_PREWARM", nullptr);
        set_env("MTMD_QWEN3TTS_LIVE_PCM", nullptr);

        auto session = std::make_unique<Session>(use_vulkan);
        static ggml_backend_dev_t cpu_only[] = {nullptr};
        auto model_params = llama_model_default_params();
        model_params.devices = cpu_only;
        model_params.n_gpu_layers = 0;
        session->model.reset(llama_model_load_from_file(model_path.c_str(), model_params));
        if (!session->model) {
            throw std::runtime_error("Could not load the Q4_K_M talker model.");
        }

        auto context_params = llama_context_default_params();
        context_params.n_ctx = context_size;
        context_params.n_batch = batch_size;
        context_params.n_ubatch = batch_size;
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        context_params.embeddings = true;
        context_params.offload_kqv = false;
        context_params.op_offload = false;
        session->context.reset(llama_init_from_model(session->model.get(), context_params));
        if (!session->context) {
            throw std::runtime_error("Could not initialize the talker context.");
        }

        auto audio_params = mtmd_context_params_default();
        audio_params.use_gpu = false;
        audio_params.n_threads = threads;
        session->audio_model.reset(mtmd_init_from_file(mmproj_path.c_str(), session->model.get(), audio_params));
        if (!session->audio_model || mtmd_gen_audio_get_info(session->audio_model.get()).type != MTMD_GEN_AUDIO_TYPE_QWEN3TTS) {
            throw std::runtime_error("Could not load the Q8_0 Qwen3-TTS audio model.");
        }
        session->generator.reset(mtmd_helper_gen_audio_init(session->context.get(), session->audio_model.get()));
        if (!session->generator) {
            throw std::runtime_error("Could not initialize speech synthesis.");
        }
        __android_log_print(ANDROID_LOG_INFO, log_tag, "LOAD %s threads=%d ms=%lld",
                            session->backend_name(), threads, elapsed_ms(start));
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::invalid_argument & error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    }
    return 0;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_qwen_tts_studio_engine_LlamaTtsEngine_nativeSynthesize(
        JNIEnv * env, jobject, jlong handle, jbyteArray text_utf8, jstring reference_wav,
        jstring language, jint max_frames) {
    try {
        auto * session = reinterpret_cast<Session *>(handle);
        if (!session) {
            throw std::runtime_error("Load the model before generating audio.");
        }
        if (max_frames <= 0) {
            throw std::invalid_argument("The generation frame limit must be positive.");
        }
        return synthesize(env, *session, get_text(env, text_utf8), get_string(env, reference_wav),
                          get_string(env, language), max_frames);
    } catch (const std::invalid_argument & error) {
        throw_java(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_qwen_tts_studio_engine_LlamaTtsEngine_nativeClose(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
