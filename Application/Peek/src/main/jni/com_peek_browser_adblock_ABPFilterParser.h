

#include <jni.h>
/* Header for class com_peek_browser_adblock_ABPFilterParser */

#ifndef _Included_com_peek_browser_adblock_ABPFilterParser
#define _Included_com_peek_browser_adblock_ABPFilterParser
#ifdef __cplusplus
extern "C" {
#endif

/*
 * Class:     com_peek_browser_adblock_ABPFilterParser
 * Method:    parseList
 * Signature: (Ljava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_com_peek_browser_adblock_ABPFilterParser_parseList
  (JNIEnv *, jobject, jstring);

/*
 * Class:     com_peek_browser_adblock_ABPFilterParser
 * Method:    stringFromJNI
 * Signature: ()Ljava/lang/String;
 */
JNIEXPORT jboolean JNICALL Java_com_peek_browser_adblock_ABPFilterParser_shouldBlock
        (JNIEnv *, jobject, jstring, jstring, jstring);

#ifdef __cplusplus
}
#endif
#endif
