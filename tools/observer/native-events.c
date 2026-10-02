/* 只记录真实 Rearm 的 JNI 出口，不替代观察器或 Rust 连接决策。 */
#include <jni.h>

/* 同步回到测试事件槽，保留连接状态和 peer-left 的差异。 */
static void record(JNIEnv *env, jstring mac, const char *kind) {
    jclass sink = (*env)->FindClass(env, "io/github/yebei199/btrearm/ObserverLifecycleTest");
    jmethodID method = (*env)->GetStaticMethodID(env, sink, "recordNative", "(Ljava/lang/String;Ljava/lang/String;)V");
    if (method == NULL) return;
    jstring event = (*env)->NewStringUTF(env, kind);
    (*env)->CallStaticVoidMethod(env, sink, method, mac, event);
    (*env)->DeleteLocalRef(env, event);
    (*env)->DeleteLocalRef(env, sink);
}

/* 原样保存生产代码对 ACL 的分类结果。 */
JNIEXPORT void JNICALL Java_io_github_yebei199_btrearm_Rearm_nativeOnConnectionChange(
    JNIEnv *env, jclass type, jstring mac, jboolean connected) {
    (void)type;
    record(env, mac, connected ? "connected" : "disconnected");
}

/* 远端离开与普通断联必须保持不同的出口。 */
JNIEXPORT void JNICALL Java_io_github_yebei199_btrearm_Rearm_nativeOnPeerLeft(
    JNIEnv *env, jclass type, jstring mac) {
    (void)type;
    record(env, mac, "peer-left");
}

/* 应用日志也记录，便于审查 unknown 和原因码，日志不驱动测试结果。 */
JNIEXPORT void JNICALL Java_io_github_yebei199_btrearm_Rearm_nativeOnError(
    JNIEnv *env, jclass type, jstring message) {
    (void)type;
    record(env, message, "log");
}
