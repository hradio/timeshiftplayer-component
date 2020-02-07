package eu.hradio.timeshiftplayer;

import android.util.Log;

import static eu.hradio.timeshiftplayer.BuildConfig.DEBUG;

/**
 * Copyright (C) 2018 IRT GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Fabian Sattler, IRT GmbH
 */
final class PcmResampler {

	private static final String TAG = "PcmResampler";

	private final int mSrcRate;
	private final int mDstRate;
	private final int mSrcChans;

	static {
		System.loadLibrary("tsresampler");
	}

	private native void info();
	private native int init(int srcRate, int dstRate, int srcChans);
	private native void deInit();
	private native byte[] resample(byte[] pcmData, int pcmDataLen);

	PcmResampler(int sourceSampling, int destinationRate, int sourceNumChannels) {
		mSrcRate = sourceSampling;
		mDstRate = destinationRate;
		mSrcChans = sourceNumChannels;

		int initDone = init(sourceSampling, destinationRate, sourceNumChannels);

		if(DEBUG) Log.d(TAG, "Resampler init: " + initDone);
	}

	byte[] resamplePcmData(byte[] pcmData) {
		return resample(pcmData, pcmData.length);
	}

	void shutDown() {
		if(DEBUG)Log.d(TAG, "Shutting down resampler");
		deInit();
	}

	int getSourceSamplingrate() {
		return mSrcRate;
	}

	int getDestinationSamplingRate() {
		return mDstRate;
	}

	int getSourceNumChans() {
		return mSrcChans;
	}
}
