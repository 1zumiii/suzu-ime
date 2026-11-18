// Android 应用自己的推理服务的 JNI 入口：librimellm_service.so。
// Kotlin 侧 com.osfans.trime.suzu.ai.AppAiService 在独立进程 :suzu_ai 里的工作线程上调用 nativeRun，
// 它阻塞到进程结束（服务被解绑后 Android 回收进程，或看门狗 Process.killProcess）。
#include <jni.h>

#include <string>
#include <vector>

#include "daemon/embedded.h"

extern "C" JNIEXPORT jint JNICALL Java_com_osfans_trime_suzu_ai_AppAiService_nativeRun(JNIEnv * env, jclass,
                                                                                        jobjectArray jargs) {
    std::vector<std::string> args;
    jsize n = jargs ? env->GetArrayLength(jargs) : 0;
    for (jsize i = 0; i < n; i++) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(jargs, i));
        if (!s) continue;
        const char * c = env->GetStringUTFChars(s, nullptr);   // 参数只有路径与数字，改良 UTF-8 与 UTF-8 相同
        args.emplace_back(c ? c : "");
        if (c) env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
    }
    return suzu::daemon::run_embedded(args);
}
