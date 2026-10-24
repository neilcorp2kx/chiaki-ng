// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#ifndef CHIAKI_JNI_AUDIO_INPUT_H
#define CHIAKI_JNI_AUDIO_INPUT_H

#include <chiaki/log.h>
#include <chiaki/opusencoder.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Microphone capture that feeds 480-sample stereo frames into the Opus encoder */
void *android_chiaki_audio_input_new(ChiakiLog *log, ChiakiOpusEncoder *encoder);
void android_chiaki_audio_input_free(void *audio_input);
bool android_chiaki_audio_input_start(void *audio_input);
void android_chiaki_audio_input_stop(void *audio_input);

#ifdef __cplusplus
}
#endif

#endif //CHIAKI_JNI_AUDIO_INPUT_H
