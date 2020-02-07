package eu.hradio.timeshiftplayer;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceAudiodataListener;
import org.omri.radioservice.RadioServiceMimeType;
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
public class TimeshiftPlayerPcmAuSdk21 extends TimeshiftPlayerAacAuSdk21 implements TimeshiftPlayerPcmAu, Serializable, RadioServiceAudiodataListener, TextualMetadataListener, VisualMetadataListener {

	private static final long serialVersionUID = -1310895166804312892L;

	private static final String TAG = "TimeshiftPlayerPcmAu21";

	@TargetApi(21)
	TimeshiftPlayerPcmAuSdk21(Context context) {
		super(context);
		if(DEBUG) Log.d(TAG, "Creating PcmAuPlayer for SDK > 21");
	}

	@Override
	void prepare(RadioService timeshiftService) throws IOException {
		/*
		if(DEBUG) Log.d(TAG, "Preparing timeshift for: " + timeshiftService.getServiceLabel());

		mPlayWhenReady = false;

		mTimeshiftService = timeshiftService;

		mEncFile = File.createTempFile("dabtmp_" + System.currentTimeMillis(), ".dab", mCacheDir);

		mOutputPath = mEncFile.getAbsolutePath();
		mFileOutStream = new FileOutputStream(mEncFile);

		mPlayerPaused = false;
		*/
		super.prepare(timeshiftService);
		mTimeshiftService.subscribe(this);
	}

	@Override
	public void setNewService(RadioService newSrv) {
		if(DEBUG)Log.d(TAG, "Setting new Service: " + newSrv.getServiceLabel() + " - " + newSrv.getRadioServiceType().toString());

		if(mTimeshiftService != null) {
			mTimeshiftService.unsubscribe(this);
		}

		mTimeshiftService = newSrv;
		mTimeshiftService.subscribe(this);
	}

	@Override
	public void stop(boolean deleteTmp) {
		if(DEBUG)Log.d(TAG, "Stopping timeshift, delTemp: " + deleteTmp);

		stopEncodingThread();
		stopGetEncodedFramesThread();

		if(mPcmResampler != null) {
			mPcmResampler.shutDown();
		}

		super.stop(deleteTmp);
	}

	@Override
	public void rawAudioData(byte[] rawData, boolean sbr, boolean ps, RadioServiceMimeType type, int numChannels, int samplingRate) {
		//nada
	}

	@Override
	@TargetApi(21)
	void creatMediaFormat() {
		if(DEBUG)Log.d(TAG, "Creating Mediaformat");

		mMediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);

		//48000 Hz
		ByteBuffer ascBuffer = ByteBuffer.wrap(new byte[]{(byte) 0x11, (byte)0x90, (byte)0x00, (byte)0x00});
		//44100 Hz
		//ByteBuffer ascBuffer = ByteBuffer.wrap(new byte[]{(byte) 0x12, (byte)0x10, (byte)0x00, (byte)0x00});
		mMediaFormat.setByteBuffer("csd-0", ascBuffer);

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
				MediaFormat outputFormat = mMediaCodec.getOutputFormat();
				mMediaCodec.start();

				if(DEBUG)Log.d(TAG, "Setting mDecode to true");
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

	/* ... */
	private CircularByteBuffer mCircBuff = null;
	private int mPcmDataRate = -1;
	private int mPcmFrameSize = -1;
	private int mNumChannels = 2;
	private PcmResampler mPcmResampler = null;
	@Override
	public void pcmAudioData(byte[] pcmData, int numChannels, int samplingRate) {
		//if(DEBUG)Log.d(TAG, "PcmData: " + pcmData.length + ", Channels: " + numChannels + ", Sampling: " + samplingRate);
		if(mPcmResampler != null) {
			if(mPcmResampler.getSourceSamplingrate() != samplingRate || mPcmResampler.getSourceNumChans() != numChannels) {
				if(DEBUG)Log.d(TAG, "Reconfiguring Resampler from " + mPcmResampler.getSourceSamplingrate() + " Hz, " +
						mPcmResampler.getSourceNumChans() + " channels to " +
						samplingRate + " Hz, " + numChannels + " channels"
				);

				mPcmResampler.shutDown();
				mPcmResampler = new PcmResampler(samplingRate, 48000, numChannels);
			}
		}

		if(mStartTimeNs == -1) {
			if(pcmData.length > 9000) {
				if(DEBUG)Log.d(TAG, "PCMData too large: " + pcmData.length);
				return;
			} else if(pcmData.length > 5000) {
				mPcmFrameSize = pcmData.length/2;
			} else {
				mPcmFrameSize = pcmData.length;
			}

			if(DEBUG)Log.d(TAG, "PCMData: " + pcmData.length + ", Channels: " + numChannels + ", Sampling: " +samplingRate);

			mStartTimeNs = System.nanoTime();
			mTimeshiftDuration = 0;
			mReadAus = 0;
			mLastDuration = 0;
			mWrittenAus = 0;

			if(DEBUG)Log.d(TAG, "Creating Resampler with: " + samplingRate + " Hz and a buffersize of " + pcmData.length);

			mPcmResampler = new PcmResampler(samplingRate, 48000, numChannels);

			//fixed output num channels
			mNumChannels = 2;

			//fixed output sampling
			mAudioTrackSamplerate = 48000;

			//mAudioTrackChannelConfig = numChannels;
			mAudioTrackChannelConfig = 2;

			//48000 Hz
			//2 Channels
			//16 bit PCM = 2 byte per sample
			//1536 kbit/s = 192 kbyte/s = 192000 byte/s

			//7680 byte / 192000 byte/s
			//mPcmDataRate = samplingRate * 2 /* bytes per frame */ * numChannels / 1000;
			mPcmDataRate = 48000 * 2 /* bytes per frame */ * 2 / 1000;

			if(DEBUG)Log.d(TAG, "Creating CircularBuffer with " + (10 * mPcmFrameSize) + " bytes of buffer");
			mCircBuff = new CircularByteBuffer(10 * mPcmFrameSize);

			//MS_AUDIO_PER_AU = mPcmFrameSize / mPcmDataRate;

			if(DEBUG)Log.d(TAG, "MS_AUDIO_PER_AU: " + MS_AUDIO_PER_AU + ", PcmFrameSize: " + mPcmFrameSize);

			byte audioByte = (byte)(0xF0);
			byte sampleByte = (byte)(0x03 << 2);
			byte sbrByte = (byte)(0x00);
			byte chanByte = (byte)(0x01);
			byte params = (byte)(audioByte | sampleByte | sbrByte | chanByte);

			try {
				mFileOutStream.write(params);
			} catch(IOException ioE) {
				if(DEBUG)ioE.printStackTrace();
			}

			mConfCodec = 63;
			mConfSampling = samplingRate;
			mConfChans = 2;
			mConfSbr = false;
			mConfPs = false;

			configureCodec();

			try {
				configureEncoder();
			} catch(IOException ioExc) {
				if(DEBUG)Log.e(TAG, "Error configuring encoder: " + ioExc.getMessage());
				if(DEBUG)ioExc.printStackTrace();
			}
		}

		if(numChannels < 2 || samplingRate < 48000) {
			mCircBuff.put(mPcmResampler.resamplePcmData(pcmData));
		} else {
			mCircBuff.put(pcmData);
		}

		encodeFrame();
		getEncodedFrame();

		if(mPlayWhenReady && !mPlaythreadRunning) {
			if(((mTimeshiftDuration/1000L)) >= 2) {
				if(DEBUG)Log.d(TAG, "Starting to play....");
				mDecode = true;
				play();

				for(TimeshiftListener listener : mListeners) {
					if(listener != null) {
						listener.started();
					}
				}
			}
		}
	}
	/* ... */

	/* Encoder */
	private MediaCodec mMediaEncoder = null;
	private MediaFormat mEncMediaFormat = null;
	private MediaCodec.BufferInfo mEncBufferInfo = null;

	private ByteBuffer[] mEncInputBuffers = null;
	private ByteBuffer[] mEncOutputBuffers = null;

	private void configureEncoder() throws IOException {
		if(DEBUG)Log.d(TAG, "Creating AudioEncoder");

		for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
			MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);

			if(!codecInfo.isEncoder()) {
				//dont need decoder
				continue;
			}

			if(DEBUG)Log.d(TAG, "AvailableEncoder Name: " + codecInfo.getName());
			for(String supType : codecInfo.getSupportedTypes()) {
				if(DEBUG)Log.d(TAG, "AvailableEncoder supported type: " + supType);
			}
		}

		mEncBufferInfo = new MediaCodec.BufferInfo();

		mMediaEncoder = MediaCodec.createEncoderByType("audio/mp4a-latm");

		mEncMediaFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2);
		mEncMediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000);
		mEncMediaFormat.setString(MediaFormat.KEY_MIME, "audio/mp4a-latm");
		mEncMediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000);
		mEncMediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectMain);

		mMediaEncoder.configure(mEncMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		mMediaEncoder.start();

		mEncInputBuffers = mMediaEncoder.getInputBuffers();
		mEncOutputBuffers = mMediaEncoder.getOutputBuffers();
	}

	private volatile boolean mEncodeThreadRunning = false;
	private Thread mEncodeThread = null;
	private void startEncodingThread() {
		if(DEBUG)Log.d(TAG, "Starting encoding thread");
		if(!mEncodeThreadRunning) {
			mEncodeThread = new Thread(mEncodingRunnable);
			mEncodeThread.start();
		}
	}

	private void stopEncodingThread() {
		if(DEBUG)Log.d(TAG, "Stopping encoding thread");
		if(mEncodeThreadRunning) {
			mEncodeThreadRunning = false;

			if(mEncodeThread != null) {
				try {
					mEncodeThread.join(0);
				} catch(InterruptedException interExc) {
					if(DEBUG)interExc.printStackTrace();
				}
			}
		}
	}

	private Runnable mEncodingRunnable = new Runnable() {
		@Override
		public void run() {
			mEncodeThreadRunning = true;
			encodeFrames();
		}
	};

	private void encodeFrames() {
		while (mEncodeThreadRunning) {
			if (mMediaEncoder != null) {
				while (mCircBuff.available() > 4096) {
					int inBuffIdx = mMediaEncoder.dequeueInputBuffer(0);
					if (inBuffIdx > -1) {
						try {
							byte[] pcmFrame = new byte[mEncInputBuffers[inBuffIdx].limit()];
							int bytesRed = mCircBuff.get(pcmFrame);
							long pts = (long) (((double) bytesRed / (double) mPcmDataRate) * 1000);
							//long pts = (long) ((double) bytesRed / (double) mPcmDataRate);
							//if(DEBUG)Log.d(TAG, "EnqueuedData: PTS: " + pts + " µs");
							if (bytesRed > 0) {
								if (mEncInputBuffers[inBuffIdx].limit() >= pcmFrame.length) {
									//if (DEBUG) Log.d(TAG, "BufferLimit: " + mInputBuffers[inBuffIdx].limit() + ", enqueuing: " + pcmFrame.length + " PCM bytes");

									mEncInputBuffers[inBuffIdx].clear();
									mEncInputBuffers[inBuffIdx].put(pcmFrame);

									mMediaEncoder.queueInputBuffer(inBuffIdx, 0, pcmFrame.length, pts, 0);
								} else {
									if (DEBUG)
										Log.d(TAG, "PcmFrame too large BufferLimit: " + mEncInputBuffers[inBuffIdx].limit() + ", enqueuing: " + pcmFrame.length + " PCM bytes");
								}
							}
						} catch (IllegalStateException illStateExc) {
							if (DEBUG)
								Log.e(TAG, "IllegalStateException while inputting buffer: " + illStateExc.getMessage());
						}
					} else {
						try {
							Thread.sleep(100);
						} catch(InterruptedException interExc) {
							if(DEBUG)interExc.printStackTrace();
						}
					}
				}
			}
		}
	}

	private void encodeFrame() {
		if(mMediaEncoder != null) {
			while(mCircBuff.available() > 4096) {
				int inBuffIdx = mMediaEncoder.dequeueInputBuffer(0);
				if (inBuffIdx > -1) {
					try {
						byte[] pcmFrame = new byte[mEncInputBuffers[inBuffIdx].limit()];
						int bytesRed = mCircBuff.get(pcmFrame);
						long pts = (long)(((double)bytesRed / (double)mPcmDataRate) * 1000);
						//long pts = (long)((double)bytesRed / (double)mPcmDataRate);
						//if(DEBUG)Log.d(TAG, "EnqueuedData: PTS: " + pts + " µs");
						if (bytesRed > 0) {
							if (mEncInputBuffers[inBuffIdx].limit() >= pcmFrame.length) {
								//if (DEBUG) Log.d(TAG, "BufferLimit: " + mInputBuffers[inBuffIdx].limit() + ", enqueuing: " + pcmFrame.length + " PCM bytes");

								mEncInputBuffers[inBuffIdx].clear();
								mEncInputBuffers[inBuffIdx].put(pcmFrame);

								mMediaEncoder.queueInputBuffer(inBuffIdx, 0, pcmFrame.length, pts, 0);
							} else {
								if (DEBUG) Log.d(TAG, "PcmFrame too large BufferLimit: " + mEncInputBuffers[inBuffIdx].limit() + ", enqueuing: " + pcmFrame.length + " PCM bytes");
							}
						}
					} catch (IllegalStateException illStateExc) {
						if (DEBUG) Log.e(TAG, "IllegalStateException while inputting buffer: " + illStateExc.getMessage());
					}
				} else {
					return;
				}
			}
		}
	}

	private volatile boolean mGetEncodeThreadRunning = false;
	private Thread mGetEncodeThread = null;
	private void startGetEncodedFramesThread() {
		if(DEBUG)Log.d(TAG, "Starting getEncoded thread");
		if(!mGetEncodeThreadRunning) {
			mGetEncodeThread = new Thread(mGetEncodedFramesRunnable);
			mGetEncodeThread.start();
		}
	}

	private void stopGetEncodedFramesThread() {
		if(DEBUG)Log.d(TAG, "Stopping getEncoded thread");
		if(mGetEncodeThreadRunning) {
			mGetEncodeThreadRunning = false;

			if(mGetEncodeThread != null) {
				try {
					mGetEncodeThread.join(0);
				} catch(InterruptedException interExc) {
					if(DEBUG)interExc.printStackTrace();
				}
			}
		}
	}

	private Runnable mGetEncodedFramesRunnable = new Runnable() {
		@Override
		public void run() {
			mGetEncodeThreadRunning = true;
			getEncodedFrames();
		}
	};

	private void getEncodedFrames() {
		while(mGetEncodeThreadRunning) {
			if (mMediaEncoder != null) {
				byte[] reArr = null;

				int outbufIdx = MediaCodec.INFO_TRY_AGAIN_LATER;
				do {
					try {
						outbufIdx = mMediaEncoder.dequeueOutputBuffer(mEncBufferInfo, 0);
					} catch (IllegalStateException illStateExc) {
						if (DEBUG) Log.e(TAG, "IllegalStateException thrown at deq outputBuffer: " + illStateExc.getMessage());
					}

					switch (outbufIdx) {
						case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED: {
							if (DEBUG) Log.i(TAG, "Outputbuffers changed");

							mEncOutputBuffers = mMediaEncoder.getOutputBuffers();
							break;
						}
						case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED: {
							MediaFormat format = mMediaEncoder.getOutputFormat();
							int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
							int chanCnt = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

							if (DEBUG) Log.i(TAG, "Outputformat Changed: Sampling: " + sampleRate + " Chans: " + chanCnt);

							break;
						}
						case MediaCodec.INFO_TRY_AGAIN_LATER: {

							break;
						}
						default: {
							if (outbufIdx >= 0) {
								ByteBuffer encBuffer = mEncOutputBuffers[outbufIdx];
								encBuffer.position(mEncBufferInfo.offset);
								encBuffer.limit(mEncBufferInfo.offset + mEncBufferInfo.size);

								reArr = new byte[mEncBufferInfo.size];
								encBuffer.get(reArr);

								//For ADTS format header
								//reArr = new byte[mEncBufferInfo.size + 7];
								//encBuffer.get(reArr, 7, mEncBufferInfo.size); //7 bytes preceeding for adts header

								encBuffer.clear();

								mMediaEncoder.releaseOutputBuffer(outbufIdx, false);

								if (((mEncBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
									try {
										//writeAdts(reArr);
										if (MS_AUDIO_PER_AU == 120) {
											//TODO inaccurate, use microseconds instead of milliseconds
											MS_AUDIO_PER_AU = mEncBufferInfo.presentationTimeUs / 1000;
											if (DEBUG)
												Log.d(TAG, "MS_AUDIO_PER_AU set to: " + MS_AUDIO_PER_AU);
										}
										writeDabTs(reArr, mEncBufferInfo.presentationTimeUs);
									} catch (IOException ioE) {
										if (DEBUG) Log.e(TAG, "Error writing adts file");
									}
								}
								break;
							} else {
								try {
									Thread.sleep(100);
								} catch(InterruptedException interExc) {
									if(DEBUG)interExc.printStackTrace();
								}
							}
						}
					}
				} while (outbufIdx >= 0);
			}
		}
	}

	private void getEncodedFrame() {
		if(mMediaEncoder != null) {
			byte[] reArr = null;

			int outbufIdx = MediaCodec.INFO_TRY_AGAIN_LATER;
			do {
				try {
					outbufIdx = mMediaEncoder.dequeueOutputBuffer(mEncBufferInfo, 0);
				} catch (IllegalStateException illStateExc) {
					if (DEBUG)
						Log.e(TAG, "IllegalStateException thrown at deq outputBuffer: " + illStateExc.getMessage());
				}

				switch (outbufIdx) {
					case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED: {
						if (DEBUG) Log.i(TAG, "Outputbuffers changed");

						mEncOutputBuffers = mMediaEncoder.getOutputBuffers();
						break;
					}
					case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED: {
						MediaFormat format = mMediaEncoder.getOutputFormat();
						int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
						int chanCnt = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

						if (DEBUG) Log.i(TAG, "Outputformat Changed: Sampling: " + sampleRate + " Chans: " + chanCnt);

						break;
					}
					case MediaCodec.INFO_TRY_AGAIN_LATER: {

						break;
					}
					default: {
						if (outbufIdx >= 0) {
							ByteBuffer encBuffer = mEncOutputBuffers[outbufIdx];
							encBuffer.position(mEncBufferInfo.offset);
							encBuffer.limit(mEncBufferInfo.offset + mEncBufferInfo.size);

							reArr = new byte[mEncBufferInfo.size];
							encBuffer.get(reArr);

							//For ADTS format header
							//reArr = new byte[mEncBufferInfo.size + 7];
							//encBuffer.get(reArr, 7, mEncBufferInfo.size); //7 bytes preceeding for adts header

							encBuffer.clear();

							mMediaEncoder.releaseOutputBuffer(outbufIdx, false);

							if (((mEncBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
								try {
									//writeAdts(reArr);
									if(MS_AUDIO_PER_AU == 120) {
										//TODO inaccurate, use microseconds instead of milliseconds
										MS_AUDIO_PER_AU = mEncBufferInfo.presentationTimeUs/1000;
										if(DEBUG)Log.d(TAG, "MS_AUDIO_PER_AU set to: " + MS_AUDIO_PER_AU);
									}
									writeDabTs(reArr, mEncBufferInfo.presentationTimeUs);
								} catch (IOException ioE) {
									if (DEBUG) Log.e(TAG, "Error writing adts file");
								}
							}
							break;
						}
					}
				}
			} while(outbufIdx >= 0);
		}
	}

	private void writeDabTs(byte[] aacData, long microSeconds) throws IOException {
		int size = aacData.length;
		byte[] sizeHdr = new byte[2];
		sizeHdr[0] = (byte)((size & 0xFF00) >> 8);
		sizeHdr[1] = (byte)(size & 0xFF);

		mFileOutStream.write(SYNC_HDR);
		mFileOutStream.write(sizeHdr);
		mFileOutStream.write(aacData);

		//need to flush to have it ready on disk
		mFileOutStream.flush();
		++mWrittenAus;
		mTimeshiftDuration += microSeconds/1000;
		//if(DEBUG)Log.d(TAG, "TimeshiftDuration: " + mTimeshiftDuration);
	}
	/* ... */

}
