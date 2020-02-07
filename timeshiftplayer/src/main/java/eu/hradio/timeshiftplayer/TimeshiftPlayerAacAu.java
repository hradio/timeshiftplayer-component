package eu.hradio.timeshiftplayer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;

import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceAudiodataListener;
import org.omri.radioservice.RadioServiceMimeType;
import org.omri.radioservice.RadioServiceRawAudiodataListener;
import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.TextualDabDynamicLabel;
import org.omri.radioservice.metadata.TextualDabDynamicLabelPlusItem;
import org.omri.radioservice.metadata.TextualMetadataListener;
import org.omri.radioservice.metadata.TextualType;
import org.omri.radioservice.metadata.Visual;
import org.omri.radioservice.metadata.VisualDabSlideShow;
import org.omri.radioservice.metadata.VisualMetadataListener;
import org.omri.radioservice.metadata.VisualType;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
public abstract class TimeshiftPlayerAacAu implements TimeshiftPlayer, Serializable, RadioServiceRawAudiodataListener, TextualMetadataListener, VisualMetadataListener {

	private static final long serialVersionUID = -6484026387419729706L;

	private static final String TAG = "TimeshiftPlayerAacAu";

	private static long NANO_PART = 1000000000L;
	private static long MIN_SUPERFRAME_SIZE = 110L;
	static long MAX_SUPERFRAME_SIZE = 2640L;

	private final int MINIMUM_SECONDS_TS_BUFFER = 2;

	MediaCodec mMediaCodec = null;
	MediaFormat mMediaFormat = null;

	ByteBuffer[] mInputBuffers = null;
	ByteBuffer[] mOutputBuffers = null;
	MediaCodec.BufferInfo mBufferInfo = null;

	int mAudioTrackSamplerate = 48000;
	int mAudioTrackChannelConfig = AudioFormat.CHANNEL_OUT_STEREO;

	int mConfCodec = 0;
	int mConfSampling = 0;
	int mConfChans = 0;
	boolean mConfSbr = false;
	boolean mConfPs = false;

	File mCacheDir = null;
	private File mCacheVisDir = null;
	private File mCacheTextDir = null;
	File mEncFile = null;
	String mOutputPath;

	//3 bytes SyncWord: 'D' 'A' 'B'
	static byte[] SYNC_HDR = {0x44, 0x41, 0x42};

	FileOutputStream mFileOutStream = null;
	RandomAccessFile mInFile = null;

	RadioService mTimeshiftService = null;

	boolean mPlayerPaused = false;

	volatile boolean mDecode = false;

	volatile boolean mPlaythreadRunning = false;

	private List<SkipItem> mSkipItems = new ArrayList<>();

	private boolean mSkipable = false;
	private boolean mItemRunning = false;
	private boolean mItemToggleState = false;

	long mTimeshiftDuration = 0;
	long MS_AUDIO_PER_AU = 120;
	long mReadAus = -1;
	long mWrittenAus = -1;

	private long mSkipPos = -1;
	private long mSeekToPos = -1;
	long mStartTimeNs = -1;
	long mLastDuration = 0;

	boolean mPlayWhenReady = false;

	CopyOnWriteArrayList<TimeshiftListener> mListeners = new CopyOnWriteArrayList<>();

	private String mLastSlsPath = null;
	private List<Pair<Long, Long> > mSkipEntries = new ArrayList<>();

	private LongSparseArray<String> mSlideshowFileSparse = new LongSparseArray<>();
	private LongSparseArray<String> mLabelFileSparse = new LongSparseArray<>();

	private Thread mPlayThread = null;

	private CopyOnWriteArrayList<RadioServiceAudiodataListener> mAudioListeners = new CopyOnWriteArrayList<>();

	/**
	 * Creates a {@link TimeshiftPlayerAacAu}
	 * @param context the Application {@link Context}. Must not be {@code null}.
	 */
	TimeshiftPlayerAacAu(Context context) {
		if(DEBUG)Log.d(TAG, "Creating AacAuPlayer");

		//The App specific cache dir is used to save the temporary timeshift file
		mCacheDir = new File(context.getExternalCacheDir(), "ts_" + System.currentTimeMillis());
		boolean dirCreated = mCacheDir.mkdir();
		if(dirCreated) {
			mCacheVisDir = new File(mCacheDir, "visuals");
			mCacheVisDir.mkdir();

			mCacheTextDir = new File(mCacheDir, "textuals");
			mCacheTextDir.mkdir();
		}

		if(DEBUG)Log.d(TAG, "FileCacheDIR: " + mCacheDir.getAbsolutePath() + " : " + dirCreated);
		if(DEBUG)Log.d(TAG, "VisCacheDIR: " + mCacheVisDir.getAbsolutePath());
		if(DEBUG)Log.d(TAG, "TexCacheDIR: " + mCacheTextDir.getAbsolutePath());
	}

	@Override
	public RadioService getRadioService() {
		return mTimeshiftService;
	}

	@Override
	public void addAudioDataListener(RadioServiceAudiodataListener pcmListener) {
		if(pcmListener != null && !mAudioListeners.contains(pcmListener)) {
			if(DEBUG)Log.d(TAG, "Adding PcmListener: " + mAudioListeners.size());
			mAudioListeners.add(pcmListener);
		}
	}

	@Override
	public void removeAudioDataListener(RadioServiceAudiodataListener pcmListener) {
		if(DEBUG)Log.d(TAG, "Removing PcmListener: " + mAudioListeners.size());
		mAudioListeners.remove(pcmListener);
	}

	/**
	 * Prepares the {@link TimeshiftPlayerAacAu}
	 * @param timeshiftService the {@link RadioService} to timeshift
	 * @throws IllegalArgumentException if the timeshiftService is not a DAB+ service. May change in further updates
	 */
	void prepare(RadioService timeshiftService) throws IOException {
		if(DEBUG) Log.d(TAG, "Preparing timeshift for: " + timeshiftService.getServiceLabel());

		mPlayWhenReady = false;

		mTimeshiftService = timeshiftService;

		mEncFile = File.createTempFile("dabtmp_" + System.currentTimeMillis(), ".dab", mCacheDir);

		mOutputPath = mEncFile.getAbsolutePath();
		mFileOutStream = new FileOutputStream(mEncFile);

		mPlayerPaused = false;
		mTimeshiftService.subscribe(this);
	}

	/**
	 * Stops the {@link TimeshiftPlayerAacAu}. You can reuse the instance by calling prepare.
	 * @param deleteTmp {@code true} if you want to delete the previously created timeshift file, {@code false} otherwise
	 */
	@Override
	public void stop(boolean deleteTmp) {
		if(DEBUG)Log.d(TAG, "Stopping timeshift, delTemp: " + deleteTmp);

		mDecode = false;

		if(mTimeshiftService != null) {
			mTimeshiftService.unsubscribe(this);
		}

		if(mFileOutStream != null) {
			try {
				mFileOutStream.close();
			} catch(IOException ioExc) {
				if(DEBUG)ioExc.printStackTrace();
			}
		}

		stopPlayThread();

		if(DEBUG)Log.d(TAG, "Stopping ongoing...");
		mPlayerPaused = false;
		mStartTimeNs = -1;
		mLastSlsPath = null;

		mSlideshowFileSparse.clear();
		mLabelFileSparse.clear();

		mConfCodec = 0;
		mConfSampling = 0;
		mConfChans = 0;
		mConfSbr = false;

		stopMediaCodec();

		if(deleteTmp) {
			if(DEBUG)Log.d(TAG, "Deleting temp folder: " + mCacheDir.getAbsolutePath());
			TimeshiftUtils.deleteFolder(mCacheDir);
		}

		for(TimeshiftListener listener : mListeners) {
			if(DEBUG)Log.d(TAG, "Calling listeners TimeshiftPlayer has stopped");
			if(listener != null) {
				listener.stopped();
			}
		}
	}

	/**
	 * Call it when you wish that playback starts immediately without calling play()
	 */
	@Override
	public void setPlayWhenReady() {
		mPlayWhenReady = true;
	}

	/**
	 * Starts the timeshift playback. You can call this only once. Use pause toggle to pause/unpause playback
	 */
	@Override
	public void play() {
		startPlayThread();
	}

	/**
	 * Pauses or un-pauses the player
	 * @param pause the wanted state: {@code true} for pause, {@code false} for un-pause
	 */
	@Override
	public void pause(boolean pause) {
		if(DEBUG)Log.d(TAG, "Pause: " + pause);
		mPlayerPaused = pause;
		if(mPlayerPaused) {
			for(TimeshiftListener listener : mListeners) {
				if(listener != null) {
					listener.paused();
				}
			}
		} else {
			for(TimeshiftListener listener : mListeners) {
				if(listener != null) {
					listener.started();
				}
			}
		}
	}

	/**
	 * Indicates if the {@link TimeshiftPlayerAacAu} is currently paused
	 * @return {@code true} if the {@link TimeshiftPlayerAacAu} is paused, {@code false} otherwise
	 */
	@Override
	public boolean isPaused() {
		return mPlayerPaused;
	}

	/**
	 * Seeks to the given position
	 * @param seekMilliseconds the wanted position in milliseconds
	 */
	@Override
	public void seek(long seekMilliseconds) {
		mSeekToPos = seekMilliseconds;
	}

	/**
	 * Returns a list of {@link SkipItem}s or an empty list
	 * @return a list of {@link SkipItem}s or an empty list
	 */
	@Override
	public List<SkipItem> getSkipItems() {
		return mSkipItems;
	}

	/**
	 * Skips to the {@link SkipItem}s state
	 * @param item the {@link SkipItem} to skip to
	 */
	@Override
	public void skipTo(SkipItem item) {
		if(item != null) {
			mSkipPos = item.getSkipPoint();
			mReadAus = item.getWrittenAus();
			mRealPosMs = mReadAus*MS_AUDIO_PER_AU;
			if(item.getSkipVisual() != null) {
				for(TimeshiftListener listener : mListeners) {
					if(listener != null) {
						listener.visual(item.getSkipVisual());
						listener.textual(item.getSkipTextual());
					}
				}
			}
		}
	}

	/**
	 * Returns the current total timeshifted duration in milliseconds
	 * @return the current total timeshifted duration in milliseconds
	 */
	@Override
	public long getDuration() {
		return mTimeshiftDuration;
	}

	@Override
	public long getCurrentPosition() {
		return mReadAus * MS_AUDIO_PER_AU;
	}

	/**
	 * Adds a {@link TimeshiftListener} to receive status updates
	 * @param listener {@link TimeshiftListener} to add
	 */
	@Override
	public void addListener(TimeshiftListener listener) {
		if(!mListeners.contains(listener)) {
			mListeners.add(listener);
		}
	}

	/**
	 * Removes a {@link TimeshiftListener}
	 * @param listener {@link TimeshiftListener} to remove
	 */
	@Override
	public void removeListener(TimeshiftListener listener) {
		mListeners.remove(listener);
	}

	private void seekToMs(long milliSecondsPos) {
		if(DEBUG)Log.d(TAG, "Seeking to: " + milliSecondsPos);

		//can't seek beyond the actual duration or to close to the end of cache file
		if(milliSecondsPos >= mTimeshiftDuration) {
			return;
		}

		try {
			if(DEBUG)Log.d(TAG, "SeekPos wanted: " + milliSecondsPos + " CurDuration: " + mTimeshiftDuration + " : " + ((System.nanoTime() - mStartTimeNs) / NANO_PART));

			long numAUsSeek = milliSecondsPos / MS_AUDIO_PER_AU;
			mReadAus = numAUsSeek;
			mRealPosMs = mReadAus * MS_AUDIO_PER_AU;

			if(DEBUG)Log.d(TAG, "SeekPos NumAus to Seek: " + numAUsSeek);

			long avgRate = (mFileOutStream.getChannel().size() - 1 - (mWrittenAus*5)) / mWrittenAus;
			long avgFilePos = mInFile.getChannel().size() / mWrittenAus * numAUsSeek;
			if(DEBUG)Log.d(TAG, "SeekPos AvgRateFilePos: " + avgFilePos);

			if(mSeekToPos < MS_AUDIO_PER_AU) {
				mInFile.seek(1);
			} else {
				mInFile.seek(avgFilePos);
			}

			//search syncHdr
			long curFileSize = mInFile.getChannel().size();
			long srchPos = avgFilePos;

			do {
				if(mInFile.readUnsignedByte() == SYNC_HDR[0]) {
					if(mInFile.readUnsignedByte() == SYNC_HDR[1]) {
						if(mInFile.readUnsignedByte() == SYNC_HDR[2]) {
							if(DEBUG)Log.d(TAG, "SeekPos searching sync found D A B at: " + mInFile.getChannel().position());
							break;
						} else {
							srchPos -= 2;
							mInFile.getChannel().position(mInFile.getChannel().position()-2);
						}
					} else {
						--srchPos;
						mInFile.getChannel().position(mInFile.getChannel().position()-1);
					}
				}

				++srchPos;
			} while (srchPos < curFileSize);


			mInFile.getFD().sync();
			mSeekToPos = -1;

			if(DEBUG)Log.d(TAG, "SeekPos SyncHdr at: " + mInFile.getChannel().position() + ", searched: " + (mInFile.getChannel().position() - avgFilePos) + " bytes");

			TextualDabDynamicLabel foundDls = null;
			VisualDabSlideShow foundSls = null;

			for(int i = 0; i < mLabelFileSparse.size(); i++) {
				long labelKey = mLabelFileSparse.keyAt(i);

				if(labelKey >= mReadAus-20 && labelKey <= mReadAus+20) {
					foundDls = (TextualDabDynamicLabel)TimeshiftUtils.deserializeTextual(mLabelFileSparse.valueAt(i));

					if(DEBUG)Log.d(TAG, "Found SeekPos DLS at Idx: " + i + ", AUPos: " + labelKey + ", ReadAus: " + mReadAus);
					break;
				}
			}

			for(int i = 0; i < mSlideshowFileSparse.size(); i++) {
				long slsKey = mSlideshowFileSparse.keyAt(i);

				if(slsKey >= mReadAus-40 && slsKey <= mReadAus+40) {
					foundSls = (VisualDabSlideShow)TimeshiftUtils.deserializeVisual(mSlideshowFileSparse.valueAt(i));

					if(DEBUG)Log.d(TAG, "Found SeekPos SLS at Idx: " + i + ", AUPos: " + slsKey + ", ReadAus: " + mReadAus);
					break;
				}
			}

			if(foundDls != null) {
				for (TimeshiftListener listener : mListeners) {
					if(listener != null) {
						listener.textual(foundDls);
					}
				}
			}
			if(foundSls != null) {
				for (TimeshiftListener listener : mListeners) {
					if(listener != null) {
						listener.visual(foundSls);
					}
				}
			}
		} catch(IOException ioE) {
			if(DEBUG)ioE.printStackTrace();
		}
	}

	private void startPlayThread() {
		if(DEBUG)Log.d(TAG, "startPlayThread()");
		if(!mPlaythreadRunning) {
			try {
				mInFile = new RandomAccessFile(mEncFile, "r");
				mInFile.skipBytes(4);
			} catch(IOException ioE) {
				if(DEBUG)Log.e(TAG, "Error opening InFile");
			}
			mPlayerPaused = false;
			mPlayThread = new Thread(PlayerRunnable);
			mPlayThread.start();

			for(TimeshiftListener listener : mListeners) {
				if(listener != null) {
					listener.started();
				}
			}
		}
	}

	private void stopPlayThread() {
		if(DEBUG)Log.d(TAG, "Stopping PlayThread");
		if(mPlaythreadRunning) {
			mPlaythreadRunning = false;

			if(mPlayThread != null) {
				try {
					if(DEBUG)Log.d(TAG, "Joining PlayThread");
					mPlayThread.join(0);
				} catch(InterruptedException iErr) {
					if(DEBUG)iErr.printStackTrace();
				}
			}
		}
	}

	void configureCodec() {
		mDecode = false;

		if(mMediaCodec != null) {
			if(DEBUG)Log.d(TAG, "Stopping MediaCodec");

			mMediaCodec.stop();
			mMediaCodec.release();
			mMediaCodec = null;
		}

		creatMediaFormat();
	}

	abstract void creatMediaFormat();

	void stopMediaCodec() {
		if(mMediaCodec != null) {
			if(DEBUG)Log.d(TAG, "Stopping MediaCodec");
			//mMediaCodec.flush();
			mMediaCodec.stop();
			mMediaCodec.release();
			mMediaCodec = null;
		} else {
			if(DEBUG)Log.w(TAG, "Stopping codec MediaCodec is null");
		}
	}

	/**/
	private Runnable PlayerRunnable = new Runnable() {
		@Override
		public void run() {
			mPlaythreadRunning = true;
			playTimeshift();
		}
	};

	private byte[] mNoBuffFreeData = null;

	abstract int getInbuffIdx();

	abstract boolean inputDecodeBuffer(byte[] encodedData, int inbuffIdx);

	abstract byte[] getDecodedBuffer();

	private long bytesToFrames(long bytes) {
		return bytes / mFrameSize;
	}

	private long framesToBytes(long frames) {
		return frames * mFrameSize;
	}

	private long framesToDurationMs(long frameCount) {
		return (frameCount * MILLIS_PER_SECOND) / mConfSampling;
	}

	private long durationMsToFrames(long durationMs) {
		return (durationMs * mConfSampling) / MILLIS_PER_SECOND;
	}

	private byte[] getNextFrame() throws EOFException, IOException{
		byte[] nextFrame = null;

		int nxtFrmSize = -1;

		long curPos = mInFile.getChannel().position();
		long curSize = mInFile.getChannel().size();
		long remainingBytes = curSize - curPos;

		//+2 for length
		//+3 for syncHdr
		if (remainingBytes < (MIN_SUPERFRAME_SIZE+2+3) ) {
			return nextFrame;
		}

		for(int i = 0; i < 3; i++) {
			if(mInFile.readUnsignedByte() != SYNC_HDR[i]) {
				mInFile.getChannel().position(curPos);
			}
		}

		nxtFrmSize = mInFile.readUnsignedShort();
		remainingBytes -= 2;

		if (remainingBytes < nxtFrmSize || nxtFrmSize == 0) {
			//if(DEBUG)Log.w(TAG, "Not enough data to read frame from pos: " + curPos + ", size: " + nxtFrmSize + ", available: " + remainingBytes);
			mInFile.getChannel().position(curPos);
			mInFile.seek(curPos);
			mInFile.getFD().sync();
			return nextFrame;
		}

		if (nxtFrmSize > 0) {
			nextFrame = new byte[nxtFrmSize];
			int bytesRed = mInFile.read(nextFrame);

			if(bytesRed != nxtFrmSize) {
				if(DEBUG)Log.w(TAG, "Short read, wanted: " + nxtFrmSize +", got: " + bytesRed);
				mInFile.getChannel().position(curPos);
				mInFile.seek(curPos);
				mInFile.getFD().sync();

				nextFrame = null;
			}
		}

		return nextFrame;
	}

	private void getDecodedFramesAndPlay(int maxDecMs) {
		int msDecoded = 0;
		while (msDecoded < maxDecMs) {
			if(!mDecode) {
				if(DEBUG)Log.w(TAG, "Stopping getDecodedFramesAndPlay");
				return;
			}
			byte[] decData = getDecodedBuffer();
			if(decData == null) {
				break;
			}

			msDecoded += framesToDurationMs(bytesToFrames(decData.length));

			for(RadioServiceAudiodataListener pcmListener : mAudioListeners) {
				//if(DEBUG)Log.d(TAG, "AudioDataOutput, Channels: " + mAudioTrackChannelConfig + ", Samplerate: " + mAudioTrackSamplerate);
				pcmListener.pcmAudioData(decData, mAudioTrackChannelConfig , mAudioTrackSamplerate);
			}
		}
	}

	private void flushMediacodec() {
		if(mMediaCodec != null) {
			mMediaCodec.flush();
			mLastInbuffIdx = -1;
		}
	}

	private static final long MILLIS_PER_SECOND = 1000L;

	private int mFrameSize = 2 * 2;
	long mRealPosMs = 0;
	private int mLastInbuffIdx = -1;
	private long mLastRealPos = 0;

	private void playTimeshift() {
		while(mPlaythreadRunning) {
			try {
				//MediaCodec and AudioTrack are not yet ready
				if(!mDecode) {
					Thread.sleep(10);
					continue;
				}

				//Seeking
				if(mSeekToPos >= 0) {
					//should clear decoded samples in decoder
					flushMediacodec();
					if(DEBUG)Log.d(TAG, "SeekPos seeking to: " + mSeekToPos);
					seekToMs(mSeekToPos);
				}
				//Skip
				if(mSkipPos >= 0) {
					//should clear decoded samples in decoder
					flushMediacodec();

					mInFile.seek(mSkipPos);
					mSkipPos = -1;
				}

				if((mTimeshiftDuration/MILLIS_PER_SECOND) > mLastDuration || mRealPosMs/MILLIS_PER_SECOND != mLastRealPos) {
					mLastDuration = (mTimeshiftDuration / MILLIS_PER_SECOND);
					mLastRealPos = mRealPosMs/MILLIS_PER_SECOND;

					for (TimeshiftListener listener : mListeners) {
						if(listener != null) {
							if(DEBUG)Log.d(TAG, "Progress: " + (mReadAus * MS_AUDIO_PER_AU) / MILLIS_PER_SECOND);
							listener.progress(((mReadAus * MS_AUDIO_PER_AU) / MILLIS_PER_SECOND), (mTimeshiftDuration / MILLIS_PER_SECOND));
						}
					}
				}

				//Pause
				if(mPlayerPaused) {
					Thread.sleep(10);
					continue;
				}

				getDecodedFramesAndPlay((int)MS_AUDIO_PER_AU);

				if(mNoBuffFreeData != null) {
					if(!inputDecodeBuffer(mNoBuffFreeData, -1)) {
						continue;
					}

					mNoBuffFreeData = null;
				}

				if(mLastInbuffIdx == -1) {
					mLastInbuffIdx = getInbuffIdx();
				}
				if(mLastInbuffIdx >= 0) {
					byte[] nextFrame = getNextFrame();
					if (nextFrame != null) {
						if (!inputDecodeBuffer(nextFrame, mLastInbuffIdx)) {
							mNoBuffFreeData = nextFrame;
						} else {
							++mReadAus;
							mLastInbuffIdx = -1;
						}
					} else {
						Thread.sleep(10);
					}
				}

				long curPoint = mReadAus;
				VisualDabSlideShow curSls = (VisualDabSlideShow)TimeshiftUtils.deserializeVisual(mSlideshowFileSparse.get(curPoint));
				TextualDabDynamicLabel curDls = (TextualDabDynamicLabel)TimeshiftUtils.deserializeTextual(mLabelFileSparse.get(curPoint));

				if(curSls != null) {
					for(TimeshiftListener listener : mListeners) {
						if(listener != null) {
							listener.visual(curSls);
						}
					}
				}
				if(curDls != null) {
					for(TimeshiftListener listener : mListeners) {
						if(listener != null) {
							listener.textual(curDls);
						}
					}
				}

			} catch(EOFException eofExc) {
				if(DEBUG)eofExc.printStackTrace();
				if(DEBUG)Log.e(TAG, "End of timeshift file at: " + mLastDuration);
			} catch(IOException ioE) {
				if(DEBUG)ioE.printStackTrace();
				if(DEBUG)Log.e(TAG, "IOException occured");
			} catch(InterruptedException interE) {
				if(DEBUG)interE.printStackTrace();
			}
		}

		Log.d(TAG, "Playthread finished...");
	}

	/* TextualListener */
	@Override
	public void newTextualMetadata(Textual textualMetadata) {
		if(textualMetadata.getType() == TextualType.METADATA_TEXTUAL_TYPE_DAB_DLS) {
			TextualDabDynamicLabel dls = (TextualDabDynamicLabel)textualMetadata;

			long skipPoint = -1;
			try {
				skipPoint = mFileOutStream.getChannel().position();
			} catch(IOException ioE) {
				if(DEBUG)ioE.printStackTrace();
			}

			String dlsPathName = TimeshiftUtils.serializeTextual(textualMetadata, mCacheTextDir+"/"+mWrittenAus);
			if(dlsPathName != null) {
				if(DEBUG)Log.d(TAG, "Saved Skiping Textual at: " + dlsPathName);
				mLabelFileSparse.append(mWrittenAus, dlsPathName);
			} else {
				if(DEBUG)Log.d(TAG, "Error saving Skiping Textual");
			}

			//Only if the Textual is Dynamic Label Plus it may have toggles
			if (dls.hasTags() && !mSkipable) {
				mSkipable = true;
				mItemToggleState = dls.itemToggled();
			}

			if(mSkipable) {
				//One may mind the itemrunning flag if you don't want to buffer any interruptions of the programme
				mItemRunning = dls.itemRunning();
				if (mItemToggleState != dls.itemToggled()) {

					//If you only want to have a SkipItem at a specific ContentType
					boolean isArtist = false;
					for(TextualDabDynamicLabelPlusItem dlItem : dls.getDlPlusItems()) {
						if (DEBUG) Log.d(TAG, "########## ItemType: " + dlItem.getDynamicLabelPlusContentType().toString());
						if (DEBUG) Log.d(TAG, "########## ItemText: " + dlItem.getDlPlusContentText());

						switch (dlItem.getDynamicLabelPlusContentType()) {
							case ITEM_ARTIST: {
								isArtist = true;
								break;
							}
							case ITEM_TITLE: {
								isArtist = true;
								break;
							}
							case ITEM_ALBUM: {
								isArtist = true;
								break;
							}
							case ITEM_BAND: {
								isArtist = true;
								break;
							}
							default: {
								break;
							}
						}
					}
					mItemToggleState = dls.itemToggled();

					//Remove this if you want to create SkipItems only at specific ContentTypes
					isArtist = true;

					if(isArtist) {
						mSkipEntries.add(new Pair<>(mWrittenAus, skipPoint));

						SkipItem skipItem = new SkipItem(mWrittenAus, skipPoint, mTimeshiftDuration, dlsPathName);
						if (mLastSlsPath != null) {
							skipItem.setSkipSls(mLastSlsPath);
						}
						mSkipItems.add(skipItem);
						for(TimeshiftListener listener : mListeners) {
							if(listener != null) {
								listener.skipItemAdded(skipItem);
							}
						}

						if (DEBUG) Log.d(TAG, "Adding SkipingPoint at: " + skipPoint + ", " + skipItem.getSkipTextual() + ", ListSize: " + mSkipItems.size());
					}

				}
			}
		}
	}

	/* VisualListener */
	@Override
	public void newVisualMetadata(Visual visualMetadata) {
		if(visualMetadata.getVisualType() == VisualType.METADATA_VISUAL_TYPE_DAB_SLS) {
			String visPath = TimeshiftUtils.serializeVisual(visualMetadata, mCacheVisDir+"/"+mWrittenAus);
			if(visPath != null) {
				mLastSlsPath = visPath;
				mSlideshowFileSparse.append(mWrittenAus, visPath);
			}
		}
	}

	/* AudioListener */
	@Override
	public void rawAudioData(byte[] rawData, boolean sbr, boolean ps, RadioServiceMimeType type, int numChannels, int samplingRate) {
		if(rawData.length == 0) {
			return;
		}

		//For DAB+ AAC services
		//AAC CoreSamplingRate
		//16 kHz AAC core sampling rate with SBR enabled 	/ AU contains audio samples for 60 ms
		//24 kHz AAC core sampling rate with SBR enabled 	/ AU contains audio samples for 40 ms
		//32 kHz AAC core sampling rate						/ AU contains audio samples for 30 ms
		//48 kHz AAC core sampling rate						/ AU contains audio samples for 20 ms

		//first audiopacket, configure everything
		if(mStartTimeNs == -1) {
			mStartTimeNs = System.nanoTime();
			mTimeshiftDuration = 0;
			mReadAus = 0;
			mLastDuration = 0;
			mWrittenAus = 0;

			if(DEBUG)Log.d(TAG, "DABHdr SBR: " + sbr + ", Sampling: " + samplingRate + ", Chans: " + numChannels);

			//1 bytes AudioParams
			//4 bits audioType; 0 = DAB MPEG 1 Layer 2; 15 = DAB+ AAC
			//2 bit sampleRate; 3 = 48 kHz; 2 = 32 kHz; 1 = 24 kHz; 0 = reserved
			//1 bit sbrUsed;
			//1 bit channels;   1 = stereo; 0 = mono
			byte audioByte = (byte)((type.getContententTypeId() == 63) ? (0xF0) : (0x00));
			byte sampleByte = (byte)((samplingRate == 48000) ? (0x03 << 2) : (samplingRate == 32000) ? (0x02 << 2) : (samplingRate == 24000) ? (0x01 << 2) : (0x00));
			byte sbrByte = (byte)(sbr ? (0x01 << 1) : (0x00));
			byte chanByte = (byte)((numChannels == 2) ? (0x01) : (0x00));
			byte params = (byte)(audioByte | sampleByte | sbrByte | chanByte);

			try {
				mFileOutStream.write(params);
			} catch(IOException ioE) {
				if(DEBUG)ioE.printStackTrace();
			}
		}

		mTimeshiftDuration += MS_AUDIO_PER_AU;

		if(mConfCodec != type.getContententTypeId() || mConfSampling != samplingRate || mConfChans != numChannels || mConfSbr != sbr) {
			mConfCodec = type.getContententTypeId();
			mConfSampling = samplingRate;
			mConfChans = numChannels;
			mConfSbr = sbr;
			mConfPs = ps;

			if(DEBUG)Log.d(TAG, "Configuring Codec Type: " + mConfCodec + ", Sampling: " + mConfSampling + ", Chans: " + mConfChans + ", SBR: " + mConfSbr);
			if(DEBUG)Log.d(TAG, "RawDataSize: " + rawData.length);

			if(DEBUG)Log.d(TAG, "Setting mAudioTrackSamplerate to: " + samplingRate);
			mAudioTrackSamplerate = samplingRate;

			configureCodec();
		}

		int size = rawData.length;
		byte[] sizeHdr = new byte[2];
		sizeHdr[0] = (byte)((size & 0xFF00) >> 8);
		sizeHdr[1] = (byte)(size & 0xFF);

		try {
			mFileOutStream.write(SYNC_HDR);
			mFileOutStream.write(sizeHdr);
			mFileOutStream.write(rawData);

			//need to flush to have it ready on disk
			mFileOutStream.flush();
			++mWrittenAus;
		} catch(IOException ioE) {
			if(DEBUG)ioE.printStackTrace();
		}

		if(mPlayWhenReady && !mPlaythreadRunning) {
			if(((mTimeshiftDuration/MILLIS_PER_SECOND)) >= MINIMUM_SECONDS_TS_BUFFER) {
				play();

				for(TimeshiftListener listener : mListeners) {
					if(listener != null) {
						listener.started();
					}
				}

			}
		}
	}
}
