#include <jvmti.h>
#include <cstdio>

// Called when this native library is attached to a running JVM
extern "C" JNIEXPORT jint JNICALL
Agent_OnAttach(JavaVM *vm, char *options, void *reserved) {
    jvmtiEnv *jvmti = nullptr;
    vm->GetEnv(reinterpret_cast<void **>(&jvmti), JVMTI_VERSION_1_2);

    printf("[jlloc-native] attached to JVM\n");

    // Phase 4 will add: GC event callbacks, safepoint hooks
    return JNI_OK;
}