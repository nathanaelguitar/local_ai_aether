#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

constexpr const char * TAG = "CanopyLlama";
constexpr int32_t BATCH_SIZE = 2048;
constexpr int32_t CONTEXT_SIZE = 20000;

std::mutex g_mutex;
llama_model * g_model = nullptr;
std::string g_model_path;

void log_callback(enum ggml_log_level level, const char * text, void *) {
    int priority = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    __android_log_write(priority, TAG, text);
}

std::string jstring_to_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars == nullptr ? std::string() : std::string(chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

llama_model * load_model(const std::string & path) {
    if (g_model != nullptr && g_model_path == path) return g_model;
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        g_model_path.clear();
    }

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(path.c_str(), params);
    if (g_model != nullptr) g_model_path = path;
    return g_model;
}

bool decode_prompt(llama_context * context, llama_batch & batch, const std::vector<llama_token> & tokens) {
    for (size_t offset = 0; offset < tokens.size(); offset += BATCH_SIZE) {
        const int32_t count = static_cast<int32_t>(std::min<size_t>(BATCH_SIZE, tokens.size() - offset));
        batch.n_tokens = count;
        for (int32_t i = 0; i < count; i++) {
            const int32_t index = static_cast<int32_t>(offset) + i;
            batch.token[i] = tokens[index];
            batch.pos[i] = index;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = i == count - 1 ? 1 : 0;
        }
        if (llama_decode(context, batch) != 0) return false;
    }
    return true;
}

std::string generate(
        const std::string & model_path,
        const std::string & mmproj_path,
        const std::string & prompt,
        int max_tokens,
        const std::vector<std::vector<unsigned char>> & images) {
    std::lock_guard<std::mutex> lock(g_mutex);
    llama_backend_init();
    llama_log_set(log_callback, nullptr);

    llama_model * model = load_model(model_path);
    if (model == nullptr) return "";

    const llama_vocab * vocab = llama_model_get_vocab(model);
    const int32_t token_count = -llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (token_count <= 0) return "";

    std::vector<llama_token> prompt_tokens(token_count);
    if (llama_tokenize(vocab, prompt.data(), static_cast<int32_t>(prompt.size()), prompt_tokens.data(), token_count, true, true) < 0) {
        return "";
    }

    llama_context_params context_params = llama_context_default_params();
    const uint32_t context_size = std::min<uint32_t>(CONTEXT_SIZE, llama_model_n_ctx_train(model));
    context_params.n_ctx = context_size;
    context_params.n_batch = BATCH_SIZE;
    context_params.n_ubatch = BATCH_SIZE;
    context_params.n_threads = std::max(2, static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN)) - 2);
    context_params.n_threads_batch = context_params.n_threads;
    llama_context * context = llama_init_from_model(model, context_params);
    if (context == nullptr) return "";

    mtmd_context * vision = nullptr;
    mtmd_input_chunks * chunks = nullptr;
    std::vector<mtmd_bitmap *> bitmaps;
    std::vector<const mtmd_bitmap *> bitmap_ptrs;
    if (!images.empty()) {
        mtmd_context_params vision_params = mtmd_context_params_default();
        vision_params.use_gpu = false;
        vision_params.n_threads = context_params.n_threads;
        vision_params.media_marker = "<__media__>";
        vision_params.batch_max_tokens = BATCH_SIZE;
        vision_params.image_max_tokens = 768;
        vision = mtmd_init_from_file(mmproj_path.c_str(), model, vision_params);
        if (vision == nullptr || !mtmd_support_vision(vision)) {
            if (vision != nullptr) mtmd_free(vision);
            llama_free(context);
            return "";
        }
        for (const auto & image : images) {
            auto wrapper = mtmd_helper_bitmap_init_from_buf(vision, image.data(), image.size(), false);
            if (wrapper.bitmap == nullptr) {
                for (auto * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
                mtmd_free(vision);
                llama_free(context);
                return "";
            }
            bitmaps.push_back(wrapper.bitmap);
            bitmap_ptrs.push_back(wrapper.bitmap);
        }
        chunks = mtmd_input_chunks_init();
        mtmd_input_text input_text{prompt.c_str(), true, true};
        if (mtmd_tokenize(vision, chunks, &input_text, bitmap_ptrs.data(), bitmap_ptrs.size()) != 0) {
            mtmd_input_chunks_free(chunks);
            for (auto * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
            mtmd_free(vision);
            llama_free(context);
            return "";
        }
    }

    llama_batch batch = llama_batch_init(BATCH_SIZE, 0, 1);
    std::string result;
    bool prompt_ok = false;
    llama_pos position = 0;
    if (vision != nullptr) {
        prompt_ok = mtmd_helper_eval_chunks(vision, context, chunks, 0, 0, BATCH_SIZE, true, &position) == 0;
    } else {
        prompt_ok = prompt_tokens.size() + static_cast<size_t>(max_tokens) < context_size && decode_prompt(context, batch, prompt_tokens);
        position = static_cast<llama_pos>(prompt_tokens.size());
    }
    if (!prompt_ok) {
        llama_batch_free(batch);
        if (chunks != nullptr) mtmd_input_chunks_free(chunks);
        for (auto * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
        if (vision != nullptr) mtmd_free(vision);
        llama_free(context);
        return "";
    }

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.8f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    for (int i = 0; i < max_tokens; i++, position++) {
        const llama_token token = llama_sampler_sample(sampler, context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;

        char buffer[512];
        const int32_t length = llama_token_to_piece(vocab, token, buffer, sizeof(buffer), 0, true);
        if (length > 0) result.append(buffer, length);

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = position;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(context, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    if (chunks != nullptr) mtmd_input_chunks_free(chunks);
    for (auto * bitmap : bitmaps) mtmd_bitmap_free(bitmap);
    if (vision != nullptr) mtmd_free(vision);
    llama_free(context);
    return result;
}

} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_nathanaelguitar_canopychat_inference_LlamaCppRuntime_generate(
        JNIEnv * env,
        jobject,
        jstring model_path,
        jstring mmproj_path,
        jstring prompt,
        jint max_tokens,
        jobjectArray image_arrays) {
    std::vector<std::vector<unsigned char>> images;
    if (image_arrays != nullptr) {
        const jsize count = env->GetArrayLength(image_arrays);
        images.reserve(count);
        for (jsize i = 0; i < count; i++) {
            auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(image_arrays, i));
            if (bytes == nullptr) continue;
            const jsize length = env->GetArrayLength(bytes);
            std::vector<unsigned char> image(length);
            env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte *>(image.data()));
            env->DeleteLocalRef(bytes);
            images.push_back(std::move(image));
        }
    }
    const std::string result = generate(
        jstring_to_string(env, model_path),
        jstring_to_string(env, mmproj_path),
        jstring_to_string(env, prompt),
        max_tokens,
        images
    );
    return env->NewStringUTF(result.c_str());
}
