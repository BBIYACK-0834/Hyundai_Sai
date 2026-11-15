#include <jni.h>
#include <whisper.h>

#include <memory>
#include <string>
#include <vector>

namespace {
struct Cancellation {
    JNIEnv * env;
    jobject session;
    jmethodID isCancelled;
};

bool isCancelled(void * data) {
    auto & cancellation = *static_cast<Cancellation *>(data);
    return cancellation.env->CallBooleanMethod(cancellation.session, cancellation.isCancelled);
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_kr_co_sai_diary_DiarySpeech_transcribe(
        JNIEnv * env, jobject session, jstring modelPath, jshortArray pcm, jint threads) {
    auto path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) return nullptr;
    auto contextParams = whisper_context_default_params();
    contextParams.use_gpu = false;
    std::unique_ptr<whisper_context, decltype(&whisper_free)> context(
            whisper_init_from_file_with_params(path, contextParams), whisper_free);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!context) return nullptr;

    auto size = env->GetArrayLength(pcm);
    std::vector<jshort> shorts(size);
    env->GetShortArrayRegion(pcm, 0, size, shorts.data());
    if (env->ExceptionCheck()) return nullptr;

    std::vector<float> samples;
    samples.reserve(size);
    for (auto sample : shorts) samples.push_back(sample / 32768.0f);

    auto type = env->GetObjectClass(session);
    Cancellation cancellation{env, session, env->GetMethodID(type, "isCancelled", "()Z")};
    if (!cancellation.isCancelled || isCancelled(&cancellation)) return nullptr;

    auto params = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
    params.language = "ko";
    params.beam_search.beam_size = 5;
    params.n_threads = threads;
    params.no_context = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.abort_callback = isCancelled;
    params.abort_callback_user_data = &cancellation;

    if (whisper_full(context.get(), params, samples.data(), size) != 0) return nullptr;

    std::string result;
    for (int i = 0; i < whisper_full_n_segments(context.get()); ++i) {
        result += whisper_full_get_segment_text(context.get(), i);
    }
    auto bytes = env->NewByteArray(static_cast<jsize>(result.size()));
    if (bytes && !result.empty()) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()),
                               reinterpret_cast<const jbyte *>(result.data()));
    }
    return bytes;
}
