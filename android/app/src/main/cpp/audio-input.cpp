// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

#include "audio-input.h"

#include <oboe/Oboe.h>

// Same frame as the desktop GUI: 480 samples (10 ms at 48 kHz), 2 channels, 16 bit
#define MICROPHONE_SAMPLES 480
#define MICROPHONE_RATE 48000

struct AudioInput: public oboe::AudioStreamDataCallback
{
	ChiakiLog *log;
	ChiakiOpusEncoder *encoder;
	oboe::ManagedStream stream;
	int16_t frame[MICROPHONE_SAMPLES * 2];
	size_t filled = 0;

	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audio_data, int32_t num_frames) override
	{
		// Phone microphones are mono, the console expects stereo: duplicate each sample.
		auto samples = reinterpret_cast<const int16_t *>(audio_data);
		for(int32_t i = 0; i < num_frames; i++)
		{
			frame[2 * filled] = samples[i];
			frame[2 * filled + 1] = samples[i];
			if(++filled == MICROPHONE_SAMPLES)
			{
				chiaki_opus_encoder_frame(frame, encoder);
				filled = 0;
			}
		}
		return oboe::DataCallbackResult::Continue;
	}
};

extern "C" void *android_chiaki_audio_input_new(ChiakiLog *log, ChiakiOpusEncoder *encoder)
{
	auto r = new AudioInput();
	r->log = log;
	r->encoder = encoder;
	return r;
}

extern "C" void android_chiaki_audio_input_free(void *audio_input)
{
	if(!audio_input)
		return;
	auto ai = reinterpret_cast<AudioInput *>(audio_input);
	ai->stream = nullptr;
	delete ai;
}

extern "C" bool android_chiaki_audio_input_start(void *audio_input)
{
	auto ai = reinterpret_cast<AudioInput *>(audio_input);
	if(ai->stream)
		return ai->stream->requestStart() == oboe::Result::OK;

	oboe::AudioStreamBuilder builder;
	builder.setDirection(oboe::Direction::Input)
		->setPerformanceMode(oboe::PerformanceMode::LowLatency)
		->setSharingMode(oboe::SharingMode::Shared)
		->setFormat(oboe::AudioFormat::I16)
		->setChannelCount(oboe::ChannelCount::Mono)
		->setSampleRate(MICROPHONE_RATE)
		->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
		// Voice preset: the device's echo cancellation and noise suppression, since the
		// phone speaker plays the game audio right next to the microphone.
		->setInputPreset(oboe::InputPreset::VoiceCommunication)
		->setDataCallback(ai);

	auto result = builder.openManagedStream(ai->stream);
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ai->log, "Microphone failed to open Oboe stream: %s", oboe::convertToText(result));
		ai->stream = nullptr;
		return false;
	}
	ai->filled = 0;
	result = ai->stream->requestStart();
	if(result != oboe::Result::OK)
	{
		CHIAKI_LOGE(ai->log, "Microphone failed to start Oboe stream: %s", oboe::convertToText(result));
		return false;
	}
	CHIAKI_LOGI(ai->log, "Microphone started (%d Hz, %d channel)", ai->stream->getSampleRate(), ai->stream->getChannelCount());
	return true;
}

extern "C" void android_chiaki_audio_input_stop(void *audio_input)
{
	auto ai = reinterpret_cast<AudioInput *>(audio_input);
	if(ai->stream)
		ai->stream->requestStop();
	CHIAKI_LOGI(ai->log, "Microphone stopped");
}
