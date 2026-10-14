#include <jni.h>
#include <cmath>
#include <mutex>
#include <memory>
#include "rife.h"
#include "gpu.h"

namespace {
std::mutex lifetime;
int sessions = 0;
bool gpuReady = false;

/** 네이티브 실패는 성공 프레임으로 취급하지 않고 Java 오류로 전달합니다. */
void fail(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
}

/** 유효한 Vulkan 장치와 실제 RIFE 4.6 모델이 있을 때만 세션을 생성합니다. */
extern "C" JNIEXPORT jlong JNICALL
Java_com_plugin_compatibility_enhancement_RifeSession_nativeCreate(JNIEnv* env, jclass, jstring path, jboolean referenceTest) {
    std::lock_guard<std::mutex> guard(lifetime);
    // CPU 기준 추론은 Java에서 별도 검사 패키지만 허용하며 앱의 GPU 대체 경로로 사용하지 않습니다.
    if (!referenceTest && !gpuReady) { ncnn::create_gpu_instance(); gpuReady = true; }
    const int gpu = referenceTest ? -1 : ncnn::get_default_gpu_index();
    if (!referenceTest && (ncnn::get_gpu_count() == 0 || gpu < 0 || ncnn::get_gpu_info(gpu).type() == 3)) {
        if (sessions == 0 && gpuReady) { ncnn::destroy_gpu_instance(); gpuReady = false; }
        fail(env, "AI 프레임 보간에 사용할 Vulkan GPU가 없습니다. 프레임 유지를 선택하세요."); return 0;
    }
    const char* directory = env->GetStringUTFChars(path, nullptr);
    if (!directory) return 0;
    std::unique_ptr<RIFE> model(new RIFE(gpu, false, false, false, 1, false, true));
    const int status = model->load(directory); env->ReleaseStringUTFChars(path, directory);
    if (status != 0) {
        model.reset(); if (sessions == 0 && gpuReady) { ncnn::destroy_gpu_instance(); gpuReady = false; }
        fail(env, "RIFE 모델을 준비할 수 없습니다. 모델을 다시 다운로드하세요."); return 0;
    }
    ++sessions; return reinterpret_cast<jlong>(model.release());
}

/** 검증한 RGB 버퍼 두 장에서 지정 시각의 실제 보간 프레임을 생성합니다. */
extern "C" JNIEXPORT void JNICALL
Java_com_plugin_compatibility_enhancement_RifeSession_nativeRun(JNIEnv* env, jclass, jlong handle,
        jobject first, jobject second, jobject result, jint width, jint height, jfloat position) {
    const jlong bytes = static_cast<jlong>(width) * height * 3;
    if (!handle || width <= 0 || height <= 0 || bytes > 50331648 || !std::isfinite(position) || position <= 0 || position >= 1
        || env->GetDirectBufferCapacity(first) < bytes || env->GetDirectBufferCapacity(second) < bytes || env->GetDirectBufferCapacity(result) < bytes) {
        fail(env, "잘못된 프레임 보간 입력입니다."); return;
    }
    auto* a = env->GetDirectBufferAddress(first); auto* b = env->GetDirectBufferAddress(second); auto* output = env->GetDirectBufferAddress(result);
    if (!a || !b || !output) { fail(env, "프레임 버퍼를 읽을 수 없습니다."); return; }
    ncnn::Mat in0(width, height, a, static_cast<size_t>(3), 1);
    ncnn::Mat in1(width, height, b, static_cast<size_t>(3), 1);
    ncnn::Mat out(width, height, output, static_cast<size_t>(3), 1);
    if (reinterpret_cast<RIFE*>(handle)->process(in0, in1, position, out) != 0) fail(env, "Vulkan GPU 프레임 보간에 실패했습니다.");
}

/** 모든 모델이 닫힌 뒤에만 공통 Vulkan 장치 자원을 해제합니다. */
extern "C" JNIEXPORT void JNICALL
Java_com_plugin_compatibility_enhancement_RifeSession_nativeClose(JNIEnv*, jclass, jlong handle) {
    if (!handle) return;
    std::lock_guard<std::mutex> guard(lifetime);
    delete reinterpret_cast<RIFE*>(handle);
    if (--sessions == 0 && gpuReady) { ncnn::destroy_gpu_instance(); gpuReady = false; }
}
