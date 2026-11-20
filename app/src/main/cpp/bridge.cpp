#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <string>
#include <vector>

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ke_ai254_Captions_decode(JNIEnv *env, jclass, jlong handle, jshortArray audio, jboolean swahili) {
    auto *context = reinterpret_cast<whisper_context *>(handle);
    auto size = env->GetArrayLength(audio);
    std::vector<jshort> pcm(size);
    env->GetShortArrayRegion(audio, 0, size, pcm.data());
    std::vector<float> samples(size);
    std::transform(pcm.begin(), pcm.end(), samples.begin(), [](jshort v) { return v / 32768.0f; });

    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.language = swahili ? "sw" : "en";
    params.n_threads = 4;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    if (whisper_full(context, params, samples.data(), size) != 0) return nullptr;
    std::string caption;
    for (int i = 0; i < whisper_full_n_segments(context); ++i)
        caption += whisper_full_get_segment_text(context, i);
    auto output = env->NewByteArray(caption.size());
    if (output) env->SetByteArrayRegion(output, 0, caption.size(), reinterpret_cast<const jbyte *>(caption.data()));
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_ke_ai254_Captions_open(JNIEnv *env, jclass, jstring path) {
    const char *filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return 0;
    auto params = whisper_context_default_params();
    params.use_gpu = false;
    auto *context = whisper_init_from_file_with_params(filename, params);
    env->ReleaseStringUTFChars(path, filename);
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT void JNICALL
Java_ke_ai254_Captions_close(JNIEnv *, jclass, jlong handle) {
    whisper_free(reinterpret_cast<whisper_context *>(handle));
}
