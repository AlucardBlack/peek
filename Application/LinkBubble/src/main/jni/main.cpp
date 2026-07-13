#include <string.h>
#include <jni.h>
#include "com_peek_browser_adblock_ABPFilterParser.h"
#include "ABPFilterParser.h"

#include <android/log.h>

#define  LOG_TAG    "ABPFilterParser"

#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)


ABPFilterParser parser;

/*
 * Class:     com_peek_browser_adblock_ABPFilterParser
 * Method:    parseList
 * Signature: (Ljava/lang/String;)V
 *
 * Parses a raw EasyList-format filter list downloaded at runtime. Filter data is
 * deep-copied internally (see Filter::data), so the input string does not need to
 * outlive this call.
 */
JNIEXPORT void JNICALL Java_com_peek_browser_adblock_ABPFilterParser_parseList(JNIEnv *env, jobject obj, jstring data) {
    const char *nativeData = env->GetStringUTFChars(data, 0);
    parser.parse(nativeData);
    env->ReleaseStringUTFChars(data, nativeData);
}

/*
 * Class:     com_peek_browser_adblock_ABPFilterParser
 * Method:    stringFromJNI
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jboolean JNICALL Java_com_peek_browser_adblock_ABPFilterParser_shouldBlock(JNIEnv *env, jobject obj, jstring baseHost, jstring input, jstring filterOption) {
    const char *nativeBaseHost = env->GetStringUTFChars(baseHost, 0);
    const char *nativeInput = env->GetStringUTFChars(input, 0);
    const char *nativeFilterOption = env->GetStringUTFChars(filterOption, 0);

    FilterOption currentOption = FONoFilterOption;
    if (0 == strcmp(nativeFilterOption, "/css")) {
        currentOption = FOStylesheet;
    } else if (0 == strcmp(nativeFilterOption, "image/")) {
        currentOption = FOImage;
    } else if (0 == strcmp(nativeFilterOption, "javascript")) {
        currentOption = FOScript;
    }

    bool shouldBlock = parser.matches(nativeInput, currentOption, nativeBaseHost);

    env->ReleaseStringUTFChars(input, nativeInput);
    env->ReleaseStringUTFChars(baseHost, nativeBaseHost);
    env->ReleaseStringUTFChars(filterOption, nativeFilterOption);

    return shouldBlock ? JNI_TRUE : JNI_FALSE;
}
