package eu.hradio.timeshiftplayer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import org.omri.radioservice.RadioServiceMimeType;
import org.omri.radioservice.RadioServiceRawAudiodataListener;
import org.omri.radioservice.metadata.TextualMetadataListener;
import org.omri.radioservice.metadata.VisualMetadataListener;

import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;

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
class TimeshiftPlayerAacAuSdk16 extends TimeshiftPlayerAacAu implements TimeshiftPlayer, Serializable, RadioServiceRawAudiodataListener, TextualMetadataListener, VisualMetadataListener {

	private static final long serialVersionUID = 2830864073407381602L;

	private static final String TAG = "TimeshiftPlayerAacAu16";

	/**
	 * Creates a {@link TimeshiftPlayerAacAuSdk16}
	 * @param context the Application {@link Context}. Must not be {@code null}.
	 */
	TimeshiftPlayerAacAuSdk16(Context context) {
		super(context);
		if(DEBUG)Log.d(TAG, "Creating AacAuPlayer for SDK 16");
	}

	int getInbuffIdx() {
		int buffIdx = -1;

		try {
			buffIdx = mMediaCodec.dequeueInputBuffer(0);
		} catch(IllegalStateException illStatExc) {
			if(DEBUG)Log.e(TAG, "IllegalStateException: " + illStatExc.getMessage());
		}

		return buffIdx;
	}

	boolean inputDecodeBuffer(byte[] encodedData, int inbuffIdx) {
		boolean enqueued = false;

		if(encodedData.length > MAX_SUPERFRAME_SIZE) {
			return enqueued;
		}

		if(mMediaCodec != null) {
			int inbufIdx;
			if(inbuffIdx >= 0) {
				inbufIdx = inbuffIdx;
			} else {
				inbufIdx = mMediaCodec.dequeueInputBuffer(0);
			}
			if (inbufIdx >= 0) {
				try {
					mInputBuffers[inbufIdx].clear();
					mInputBuffers[inbufIdx].put(encodedData);

					mMediaCodec.queueInputBuffer(inbufIdx, 0, encodedData.length, 0, 0);
					enqueued = true;
				} catch(IllegalStateException illStateExc) {
					if(DEBUG)Log.e(TAG, "IllegalStateException while inputting buffer: " + illStateExc.getMessage());
				}
			} else {
				if(DEBUG)Log.i(TAG, "######### No inputbuffer free!");
			}
		} else {
			if(DEBUG)Log.w(TAG, "MediaCodec is null at inputDecodeBuffer()");
		}

		return enqueued;
	}

	byte[] getDecodedBuffer() {
		byte[] reArr = null;

		if(mMediaCodec != null) {
			int outbufIdx = MediaCodec.INFO_TRY_AGAIN_LATER;
			try {
				outbufIdx = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 0);
			} catch(IllegalStateException illStateExc) {
				if(DEBUG)Log.e(TAG, "IllegalStateException thrown at deq outputBuffer: " + illStateExc.getMessage());
			}
			switch (outbufIdx) {
				case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED: {
					if(DEBUG)Log.i(TAG, "Outputbuffers changed");

					mOutputBuffers = mMediaCodec.getOutputBuffers();
					break;
				}
				case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED: {
					MediaFormat format = mMediaCodec.getOutputFormat();
					int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
					int chanCnt = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

					if(chanCnt == 1) {
						mAudioTrackChannelConfig = AudioFormat.CHANNEL_OUT_MONO;
					}

					mAudioTrackSamplerate = sampleRate;

					if(DEBUG)Log.i(TAG, "Outputformat Changed: Sampling: " + sampleRate + " Chans: " + chanCnt);

					break;
				}
				case MediaCodec.INFO_TRY_AGAIN_LATER: {

					break;
				}
				default: {
					if(outbufIdx >= 0) {
						ByteBuffer pcmBuffer = mOutputBuffers[outbufIdx];

						if(pcmBuffer != null) {
							reArr = new byte[mBufferInfo.size];
							pcmBuffer.get(reArr);
							pcmBuffer.clear();
						}

						mMediaCodec.releaseOutputBuffer(outbufIdx, false);
						break;
					}
				}
			}
		} else {
			if(DEBUG)Log.w(TAG, "MediaCodec is null at getDecodedBuffer()");
		}

		return reArr;
	}

	void creatMediaFormat() {
		if(DEBUG)Log.d(TAG, "Creating Mediaformat");

		if(mConfCodec == RadioServiceMimeType.AUDIO_AAC_DAB_AU.getContententTypeId()) {
			mMediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", mConfSampling, mConfChans);
		}

		if(mConfCodec == RadioServiceMimeType.AUDIO_AAC_DAB_AU.getContententTypeId()) {
			byte[] ascBytes = null;
			if(mConfSbr) {
				if(!mConfPs) {
					if(DEBUG)Log.d(TAG, "Configuring ASC with SBR!");
					ascBytes = new byte[]{(byte) 0x2B, (byte)0x11, (byte)0x8A, (byte)0x00};
				}else {
					if(DEBUG)Log.d(TAG, "Configuring ASC with SBR and PS!");
					ascBytes = new byte[]{(byte) 0xEB, (byte) 0x11, (byte) 0x8A, (byte) 0x00};
				}
			} else {
				if(DEBUG)Log.d(TAG, "Configuring ASC without SBR!");
				ascBytes = new byte[]{(byte) 0x11, (byte)0x94, (byte)0x00, (byte)0x00};
			}

			if(mConfSampling == 32000) {
				if(DEBUG)Log.d(TAG, "Configuring ASC for 32 kHz!");
				ascBytes[0] = (byte)(ascBytes[0] + 1);
				if(mConfSbr) {
					if(DEBUG)Log.d(TAG, "Configuring ASC for 32 kHz and SBR!");
					ascBytes[1] = (byte)(ascBytes[1] + 1);
				}
			}

			if(mConfChans == 1) {
				if(DEBUG)Log.d(TAG, "Configuring ASC for Mono!");
				ascBytes[1] = (byte)(ascBytes[1] - 8);
			}

			ByteBuffer ascBuffer = ByteBuffer.wrap(ascBytes);
			mMediaFormat.setByteBuffer("csd-0", ascBuffer);
		}

		try {
			for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
				MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

				if(codecInfo.isEncoder()) {
					//dont need encoder
					continue;
				}

				if(DEBUG)Log.d(TAG, "AvailableCodec Name: " + codecInfo.getName());

				if(codecInfo.getName().equals("OMX.google.aac.decoder")) {
					if(DEBUG)Log.d(TAG, "Found Google AAC decoder...choosing this one...");
					mMediaCodec = MediaCodec.createByCodecName(codecInfo.getName());
					break;
				}
			}

			if(mMediaCodec == null) {
				mMediaCodec = MediaCodec.createDecoderByType(mMediaFormat.getString(MediaFormat.KEY_MIME));
			}
		} catch(IOException ioExc) {
			if(DEBUG)Log.e(TAG, "Decoder could not be created");
			ioExc.printStackTrace();
		}
		if(mMediaCodec != null) {
			try {
				mBufferInfo = new MediaCodec.BufferInfo();

				mMediaCodec.configure(mMediaFormat, null, null, 0);
				mMediaCodec.start();

				mInputBuffers = mMediaCodec.getInputBuffers();
				mOutputBuffers = mMediaCodec.getOutputBuffers();

				mDecode = true;
			} catch (IllegalArgumentException illArgExc ){
				if(DEBUG)Log.e(TAG, "MediaCodec IllegalArgumentException thrown: " + illArgExc.getMessage());
			} catch(IllegalStateException illStateExc) {
				if(DEBUG)Log.e(TAG, "MediaCodec IllegalStateException thrown: " + illStateExc.getMessage());
			}
		} else {
			if(DEBUG)Log.e(TAG, "Configuring MediaCodec is null!");
		}
	}
}
