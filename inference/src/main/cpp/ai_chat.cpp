#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <atomic>
#include <mutex>
#include <exception>
#include <stdexcept>
#include <unistd.h>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

// Conservative Android default: memory is shared with the system and other apps.
constexpr int   DEFAULT_CONTEXT_SIZE    = 2048;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   SYSTEM_PROMPT_RESERVE   = 512;  // Leave room for an actual chat turn
constexpr int   USER_PREFILL_RESERVE    = 32;   // Leave room for first response logits
constexpr int   BATCH_SIZE              = 128;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static common_batch                       g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static int g_effective_context_size = DEFAULT_CONTEXT_SIZE;

// Record a bounded copy of llama.cpp native ERROR/WARN messages so Android UI
// can report why a model load failed without privileged logcat access.
static std::mutex g_diagnostic_mutex;
static std::string g_load_diagnostics;
static std::atomic<bool> g_capture_load_log{false};
static constexpr size_t MAX_LOAD_DIAGNOSTIC_BYTES = 8192;

static void pocket_recording_log_callback(enum ggml_log_level level, const char* message, void* user) {
    aichat_android_log_callback(level, message, user);
    if (!message || !g_capture_load_log.load(std::memory_order_relaxed)) return;
    if (level != GGML_LOG_LEVEL_ERROR && level != GGML_LOG_LEVEL_WARN) return;
    std::lock_guard<std::mutex> guard(g_diagnostic_mutex);
    g_load_diagnostics += message;
    if (g_load_diagnostics.size() > MAX_LOAD_DIAGNOSTIC_BYTES) {
        g_load_diagnostics.erase(0, g_load_diagnostics.size() - MAX_LOAD_DIAGNOSTIC_BYTES);
    }
}

static void reset_load_diagnostics() {
    std::lock_guard<std::mutex> guard(g_diagnostic_mutex);
    g_load_diagnostics.clear();
}

// All native exceptions must terminate at the JNI boundary, NEVER cross into ART.
// Exposing the actual stage and reason turns SIGABRT into a recoverable error.
static void set_native_error(const char *stage, const char *reason) noexcept {
    LOGe("Pocket AI native %s failed: %s", stage, reason ? reason : "unknown");
    try {
        std::lock_guard<std::mutex> guard(g_diagnostic_mutex);
        g_load_diagnostics = std::string(stage) + ": " + (reason ? reason : "unknown error");
    } catch (...) {
        // The diagnostic itself must not throw across JNI, including low-RAM cases.
    }
}

static std::string java_string(JNIEnv *env, jstring input) {
    if (!input) {
        throw std::invalid_argument("Java input string is null");
    }
    const char *utf = env->GetStringUTFChars(input, nullptr);
    if (!utf) {
        throw std::runtime_error("Cannot read Java UTF-8 string");
    }
    // Copy and release before any chat-template evaluation can throw.
    std::string value;
    try {
        value = utf;
    } catch (...) {
        env->ReleaseStringUTFChars(input, utf);
        throw;
    }
    env->ReleaseStringUTFChars(input, utf);
    return value;
}

static void throw_java_native_error(JNIEnv *env, const char *stage, const char *message) noexcept {
    set_native_error(stage, message);
    // The class is part of Android's boot classpath. JNI will translate the
    // error into Kotlin's normal exception handling, without killing the process.
    jclass io_exception = env->FindClass("java/io/IOException");
    if (io_exception) {
        env->ThrowNew(io_exception, "Native GGUF processing failed; see device logs for details");
        env->DeleteLocalRef(io_exception);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_lastLoadError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(g_diagnostic_mutex);
    // JNI NewStringUTF requires modified UTF-8. Native log lines are not
    // guaranteed to meet that requirement (especially when truncated).
    // Return safely sanitized, printable diagnostics instead.
    std::string safe;
    safe.reserve(g_load_diagnostics.size());
    for (unsigned char byte : g_load_diagnostics) {
        if ((byte >= 32 && byte <= 126) || byte == '\n' || byte == '\r' || byte == '\t') {
            safe.push_back(static_cast<char>(byte));
        } else {
            safe.push_back('?');
        }
    }
    return env->NewStringUTF(safe.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    llama_log_set(pocket_recording_log_callback, nullptr);

    // Dynamic CPU variant loader expects actual .so files in nativeLibraryDir.
    // The app must set jniLibs.useLegacyPackaging = true to extract those files.
    const auto *raw_dir = env->GetStringUTFChars(nativeLibDir, nullptr);
    if (!raw_dir) {
        LOGe("Unable to read nativeLibraryDir from Android");
        return 0;
    }
    const std::string backend_dir(raw_dir);
    env->ReleaseStringUTFChars(nativeLibDir, raw_dir);

    LOGi("Initializing safe Android ARMv8.0 CPU backend from %s", backend_dir.c_str());
    // Start with the most compatible CPU backend. This is intentionally slower
    // than a v9 variant; we need a reliable baseline before enabling optimizations.
    const std::string baseline = backend_dir + "/libggml-cpu-android_armv8.0_1.so";
    if (access(baseline.c_str(), R_OK) == 0) {
        ggml_backend_load(baseline.c_str());
    } else {
        LOGe("Baseline ARM64 CPU backend missing: %s", baseline.c_str());
    }
    if (!ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU)) {
        LOGw("Baseline backend unavailable; trying automatic backend discovery");
        ggml_backend_load_all_from_path(backend_dir.c_str());
    }

    const auto registered = ggml_backend_reg_count();
    const auto cpu = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (!cpu) {
        LOGe("No CPU backend registered in %s (registered backends: %zu)", backend_dir.c_str(), registered);
        return 0;
    }

    llama_backend_init();
    LOGi("GGML initialized: %zu backends; CPU device: %s",
         registered, ggml_backend_dev_name(cpu));
    return static_cast<jint>(registered);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    try {
    llama_model_params model_params = llama_model_default_params();
    // Low-RAM Android profile: memory-map weights; avoid additional CPU weight
    // repacking allocations and any unintended GPU offload.
    model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
    model_params.use_extra_bufts = false;
    model_params.n_gpu_layers = 0;

    const std::string model_path = java_string(env, jmodel_path);
    LOGd("%s: Starting GGUF model load", __func__);

    reset_load_diagnostics();
    g_capture_load_log.store(true, std::memory_order_release);
    auto *model = llama_model_load_from_file(model_path.c_str(), model_params);
    g_capture_load_log.store(false, std::memory_order_release);
    if (!model) {
        LOGe("llama_model_load_from_file returned null (native details available via lastLoadError)");
        return 1;
    }
    g_model = model;
    return 0;
    } catch (const std::exception &e) {
        g_capture_load_log.store(false, std::memory_order_release);
        set_native_error("model_load", e.what());
        return 3;
    } catch (...) {
        g_capture_load_log.store(false, std::memory_order_release);
        set_native_error("model_load", "Unknown native C++ exception");
        return 4;
    }
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    const int effective_ctx = (trained_context_size > 0)
        ? std::min(n_ctx, trained_context_size) : n_ctx;
    ctx_params.n_ctx = std::max(256, effective_ctx);
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    try {
    reset_load_diagnostics();
    g_capture_load_log.store(true, std::memory_order_release);
    auto *context = init_context(g_model);
    g_capture_load_log.store(false, std::memory_order_release);
    if (!context) { return 1; }
    g_context = context;
    g_effective_context_size = static_cast<int>(llama_n_ctx(context));
    LOGi("Android safe inference: context=%d, batch=%d, max_threads=%d",
         g_effective_context_size, BATCH_SIZE, N_THREADS_MAX);
    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    return 0;
    } catch (const std::exception &e) {
        g_capture_load_log.store(false, std::memory_order_release);
        set_native_error("context_prepare", e.what());
        return 3;
    } catch (...) {
        g_capture_load_log.store(false, std::memory_order_release);
        set_native_error("context_prepare", "Unknown native C++ exception");
        return 4;
    }
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch batch(context);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            batch.add(0, i, 0, false);
        }

        batch.set_output(batch.size() - 1, true);
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            batch.clear();
            for (j = 0; j < pl; j++) {
                batch.add(0, i, j, true);
            }

            if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
// Direct-answer mode by default: GGUF templates such as Spark-X2.6 otherwise
// inject high-effort thinking instructions and start generation with <think>.
static bool g_enable_thinking = false;
// MCP tools are bound to the actual GGUF/Jinja system template, not prepended
// as instructions to an arbitrary user message. Changes require KV reset.
static std::vector<common_chat_tool> g_chat_tools;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/** Drop the oldest half of non-system decoded KV history, preserving the
 * pinned system-prompt prefix. New prompts are shifted only before prefill. */
static bool shift_context() {
    // Preserve system prompt. When no non-system history can be discarded,
    // fail with a recoverable native error instead of decoding past n_ctx.
    const int non_system_tokens = current_position - system_prompt_position;
    if (non_system_tokens < 32) {
        LOGe("%s: No room to shift (position=%d, system=%d)", __func__,
             current_position, system_prompt_position);
        return false;
    }
    const int n_discard = non_system_tokens / 2;
    LOGi("%s: Discarding %d previous tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0,
                        system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0,
                         system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Shifted context to position %d", __func__, current_position);
    return true;
}

static std::string chat_render(const std::vector<common_chat_msg> &messages, bool generation_prefix) {
    // common_chat_format_single() cannot configure enable_thinking. Its default
    // silently activates high-effort reasoning in templates like Spark-X2.6.
    common_chat_templates_inputs inputs;
    inputs.messages = messages;
    inputs.use_jinja = true;
    inputs.enable_thinking = g_enable_thinking;
    inputs.tools = g_chat_tools;
    inputs.tool_choice = COMMON_CHAT_TOOL_CHOICE_AUTO;
    inputs.parallel_tool_calls = false;
    inputs.add_generation_prompt = generation_prefix;
    return common_chat_templates_apply(g_chat_templates.get(), inputs).prompt.str();
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;

    const std::string rendered_past = chat_msgs.empty() ? "" : chat_render(chat_msgs, false);
    auto updated_msgs = chat_msgs;
    updated_msgs.push_back(new_msg);
    const std::string rendered_updated = chat_render(updated_msgs, role == ROLE_USER);

    // Incremental KV-cache decoding relies on the new chat template sharing
    // exactly the same prefix as the already-decoded message history.
    if (!rendered_past.empty() && rendered_updated.compare(0, rendered_past.size(), rendered_past) != 0) {
        throw std::runtime_error("Chat template rewrote its previous messages. Start a new conversation.");
    }
    const std::string formatted = rendered_updated.substr(rendered_past.size());
    chat_msgs.push_back(std::move(new_msg));
    LOGi("%s: Formatted %s message (%zu chars, thinking=%s)",
         __func__, role.c_str(), formatted.size(), g_enable_thinking ? "on" : "off");
    return formatted;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_setThinkingEnabledNative(
        JNIEnv * /*env*/, jobject /*unused*/, jboolean enabled) {
    // Called on the single-threaded llamaDispatcher before system prompt decode.
    // Changing this mid-conversation requires rebuilding the prompt/KV cache.
    g_enable_thinking = enabled == JNI_TRUE;
    LOGi("GGUF chat thinking mode: %s", g_enable_thinking ? "enabled" : "disabled");
}

/**
 * Completion loop's short-term states:
 * - per-response generation token limit (independent of KV positions)
 * - token chars caching
 * - current assistant message being generated
 */
static int g_generated_tokens = 0;
static int g_generation_token_limit = 0;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;
static bool g_pending_assistant = false;

// Commit generated assistant text once, including partial Stop-button output.
static void finalize_assistant_turn() {
    if (!g_pending_assistant) return;
    chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
    g_pending_assistant = false;
}

static void reset_short_term_states() {
    g_generated_tokens = 0;
    g_generation_token_limit = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
    assistant_ss.clear();
    g_pending_assistant = false;
}

static int decode_tokens_in_batches(
        llama_context *context,
        common_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Track actual KV position per processed batch. A context shift changes
    // this position, so the old start_pos+i arithmetic was unsafe.
    LOGd("%s: Decode %d tokens from position %d", __func__, (int) tokens.size(), start_pos);
    if (start_pos != current_position) {
        LOGe("%s: Native KV position mismatch", __func__);
        return 1;
    }
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();

        // Context shifting is deliberately prohibited in the middle of
        // decoding a NEW prompt. Otherwise the prompt's beginning (including
        // selected MCP tool definitions) could be lost before sampling.
        if (current_position + cur_batch_size >= g_effective_context_size - OVERFLOW_HEADROOM) {
            LOGe("%s: Prompt batch exceeds reserved KV space", __func__);
            return 1;
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const bool want_logit = compute_last_logit && (i + j == (int) tokens.size() - 1);
            batch.add(token_id, current_position + j, 0, want_logit);
        }
        const int decode_result = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d", __func__, decode_result);
            return 1;
        }
        current_position += cur_batch_size;
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt,
        jstring jtools_json
) {
    try {
    reset_load_diagnostics();
    // Parse the exact, validated, user-selected MCP function definitions.
    // Nothing here carries credentials or authorizes actual tool execution.
    const std::string raw_tools = java_string(env, jtools_json);
    if (raw_tools.size() > 14000) {
        throw std::invalid_argument("MCP function catalog too large");
    }
    const auto parsed_json = common_json::parse(raw_tools);
    if (!parsed_json.is_array() || parsed_json.size() > 5) {
        throw std::invalid_argument("MCP function catalog must be an array of <=5 tools");
    }
    const auto parsed_tools = common_chat_tools_parse_oaicompat(parsed_json);
    for (const auto &tool : parsed_tools) {
        if (tool.name.empty() || tool.name.size() > 128 ||
                tool.description.size() > 500 || tool.parameters.size() > 8000) {
            throw std::invalid_argument("Invalid MCP function definition limits");
        }
    }
    // Reset long-term & short-term states BEFORE adding tools to the
    // system block: Jinja prefixes MUST stay identical throughout the chat.
    reset_long_term_states();
    reset_short_term_states();
    g_chat_tools = parsed_tools;
    LOGi("Pocket AI native MCP tool binding: %zu functions, thinking=%s",
         g_chat_tools.size(), g_enable_thinking ? "on" : "off");
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const std::string system_prompt = java_string(env, jsystem_prompt);
    LOGd("%s: System prompt length: %zu bytes", __func__, system_prompt.size());
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Reserve context for real user turns. Never discard system-prompt tokens.
    const int max_system_tokens = g_effective_context_size -
            OVERFLOW_HEADROOM - SYSTEM_PROMPT_RESERVE;
    if ((int) system_tokens.size() >= max_system_tokens) {
        set_native_error("system_prompt", "System-Prompt zu lang fuer lokalen Kontext");
        LOGe("%s: System prompt uses %d tokens, max allowed: %d",
             __func__, (int) system_tokens.size(), max_system_tokens);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // decode_tokens_in_batches advanced the real KV position.
    system_prompt_position = current_position;
    return 0;
    } catch (const std::exception &e) {
        set_native_error("system_prompt", e.what());
        return 3;
    } catch (...) {
        set_native_error("system_prompt", "Unknown native C++ exception");
        return 4;
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    try {
    reset_load_diagnostics();
    // Complete any partial assistant left by a previously cancelled Flow.
    finalize_assistant_turn();
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const std::string user_prompt = java_string(env, juser_prompt);
    LOGd("%s: User prompt length: %zu bytes", __func__, user_prompt.size());
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Do not accept a prompt that cannot fit in full after preserving the
    // system tokens. The model must never act on a partially decoded command.
    const int user_prompt_size = (int) user_tokens.size();
    const int capacity = g_effective_context_size - OVERFLOW_HEADROOM;
    const int max_prompt_tokens = capacity - system_prompt_position -
            USER_PREFILL_RESERVE - 1;
    if (user_prompt_size > max_prompt_tokens) {
        set_native_error("user_prompt",
                         "MCP-/Chat-Anfrage zu lang: weniger Tools waehlen oder Anfrage kuerzen");
        return 1;
    }
    // Shift only completed PRIOR turns before decoding the entire new user
    // prompt. This prevents stripping newly supplied tool argument schemas.
    while (current_position + user_prompt_size + USER_PREFILL_RESERVE >= capacity) {
        if (!shift_context()) {
            set_native_error("user_prompt",
                             "Zu wenig Kontext: Chat zuruecksetzen oder weniger MCP-Tools waehlen");
            return 1;
        }
    }

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // The prefill loop already advanced current_position (including shifts).
    // Count output tokens separately; shifting the KV cache must not extend
    // the generation budget or create a never-ending generation loop.
    g_generated_tokens = 0;
    g_generation_token_limit = std::max(1, (int) n_predict);
    g_pending_assistant = true;
    return 0;
    } catch (const std::exception &e) {
        set_native_error("user_prompt", e.what());
        return 3;
    } catch (...) {
        set_native_error("user_prompt", "Unknown native C++ exception");
        return 4;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_finalizeGenerationNative(
        JNIEnv *env, jobject /*unused*/) {
    try {
        finalize_assistant_turn();
    } catch (const std::exception &e) {
        throw_java_native_error(env, "finalize_generation", e.what());
    } catch (...) {
        throw_java_native_error(env, "finalize_generation",
                                "Unknown native C++ exception");
    }
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    try {
    // Generation length is token-count based, not an absolute KV position.
    if (!g_pending_assistant) return nullptr;
    // On a token-budget stop, finalize assistant history like on EOG.
    if (g_generated_tokens >= g_generation_token_limit) {
        LOGi("%s: Generation budget reached (%d tokens)", __func__, g_generated_tokens);
        finalize_assistant_turn();
        return nullptr;
    }
    if (current_position >= g_effective_context_size - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full during generation; shifting...", __func__);
        if (!shift_context()) {
            finalize_assistant_turn();
            return nullptr;
        }
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    g_batch.clear();
    g_batch.add(new_token_id, current_position, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update KV and output-token positions independently.
    current_position++;
    g_generated_tokens++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        finalize_assistant_turn();
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
    } catch (const std::exception &e) {
        throw_java_native_error(env, "generation", e.what());
        return nullptr;
    } catch (...) {
        throw_java_native_error(env, "generation", "Unknown native C++ exception");
        return nullptr;
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();
    g_chat_tools.clear();

    // This must be safe even after a partially failed model/context allocation.
    // The original bridge only cleaned up a fully ready model and leaked memory
    // when context preparation failed.
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }
    g_chat_templates.reset();
    g_batch = common_batch();
    if (g_context) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_effective_context_size = DEFAULT_CONTEXT_SIZE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
