package eu.hradio.timeshiftplayer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
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
import java.util.ArrayList;
import java.util.List;

import de.irt.dabaudiodecoderplugininterface.IDabPluginCallback;
import de.irt.dabaudiodecoderplugininterface.IDabPluginInterface;

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
public class TimeshiftPlayerMpeg2 implements TimeshiftPlayer, Serializable, RadioServiceRawAudiodataListener, TextualMetadataListener, VisualMetadataListener {

	private static final long serialVersionUID = 9107718318244172337L;

	private final static String TAG = "TimeshiftPlayerMpeg2";

	private Context mContext;

	private RadioService mTimeshiftService = null;

	private boolean mPlayWhenReady = false;
	private boolean mPlayerPaused = false;

	private FileOutputStream mFileOutStream = null;
	private RandomAccessFile mInFile = null;

	private File mCacheDir = null;
	private File mCacheVisDir = null;
	private File mCacheTextDir = null;
	private File mEncFile = null;
	private String mOutputPath;

	private boolean mPlaythreadRunning = false;
	private Thread mPlayThread = null;

	private long mSkipPos = -1;
	private long mSeekToPos = -1;
	private long mStartTimeNs = -1;
	private long mLastDuration = 0;

	private long mReadAus = -1;
	private long mMsAudioPerAu = -1;
	private long mTimeshiftDuration = 0;
	private long mWrittenAus = -1;
	private int mMpegFrameSize = -1;

	private boolean mSkipable = false;
	private boolean mItemRunning = false;
	private boolean mItemToggleState = false;

	private boolean mEofPause = false;

	private String mLastSlsPath = null;
	private List<Pair<Long, Long>> mSkipEntries = new ArrayList<>();

	private List<SkipItem> mSkipItems = new ArrayList<>();

	private List<TimeshiftListener> mListeners = new ArrayList<>();

	private LongSparseArray<String> mSlideshowFileSparse = new LongSparseArray<>();
	private LongSparseArray<String> mLabelFileSparse = new LongSparseArray<>();

	private int mConfChans = 0;
	private int mConfSampling = 0;

	private List<RadioServiceAudiodataListener> mAudioListeners = new ArrayList<>();

	//Decoder plugin
	private boolean mHasMpegDecPlug = false;
	private IDabPluginInterface mDecoderService;
	private DabDecoderServiceConnection mDecoderConnection;

	TimeshiftPlayerMpeg2(Context context) {
		mContext = context;

		mCacheDir = new File(context.getExternalCacheDir(), "ts_" + System.currentTimeMillis());
		boolean dirCreated = mCacheDir.mkdir();
		if(dirCreated) {
			mCacheVisDir = new File(mCacheDir, "visuals");
			mCacheVisDir.mkdir();

			mCacheTextDir = new File(mCacheDir, "textuals");
			mCacheTextDir.mkdir();
		}

		if(DEBUG) {
			Log.d(TAG, "FileCacheDIR: " + mCacheDir.getAbsolutePath() + " : " + dirCreated);
			Log.d(TAG, "VisCacheDIR: " + mCacheVisDir.getAbsolutePath());
			Log.d(TAG, "TexCacheDIR: " + mCacheTextDir.getAbsolutePath());
		}

		mHasMpegDecPlug = mpegDecPluginInstalled();
	}

	@Override
	public RadioService getRadioService() {
		return mTimeshiftService;
	}

	@Override
	public void addAudioDataListener(RadioServiceAudiodataListener pcmListener) {
		if(pcmListener != null && !mAudioListeners.contains(pcmListener)) {
			mAudioListeners.add(pcmListener);
		}
	}

	@Override
	public void removeAudioDataListener(RadioServiceAudiodataListener pcmListener) {
		mAudioListeners.remove(pcmListener);
	}

	/* **************** Decoder Service **************************** */
	private boolean mpegDecPluginInstalled() {
		if(DEBUG)Log.d(TAG, "Searching installed Codec Plugins!");

		PackageManager packageManager = mContext.getPackageManager();
		List<ApplicationInfo> apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
		for(ApplicationInfo appInfo : apps) {
			if(appInfo.packageName.equalsIgnoreCase("de.irt.dabmpg123decoderplugin")) {
				if(DEBUG)Log.d(TAG, "Found MPEG L2 Codec Plugin...binding service!");
				return bindDecoderService();
			}
		}

		return false;
	}

	private boolean bindDecoderService() {
		if(DEBUG)Log.d(TAG, "Binding service!");

		mDecoderConnection = new DabDecoderServiceConnection();
		final Intent srvIntent = new Intent("de.irt.dabmpg123decoderplugin.Mpg123Decoder");
		srvIntent.setPackage("de.irt.dabmpg123decoderplugin");

		return mContext.getApplicationContext().bindService(srvIntent, mDecoderConnection, Context.BIND_AUTO_CREATE);
	}

	class DabDecoderServiceConnection implements ServiceConnection {

		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			if(DEBUG)Log.d(TAG, "onServiceConnected: " + name.toString());

			mDecoderService = IDabPluginInterface.Stub.asInterface(service);
			try {
				mDecoderService.setCallback(mDecSrvCallback);
			} catch(RemoteException remExc) {
				remExc.printStackTrace();
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName name) {
			if(DEBUG)Log.d(TAG, "onServiceDisconnected: " + name.toString());
			mDecoderService = null;
		}
	}

	private final IDabPluginCallback.Stub mDecSrvCallback = new IDabPluginCallback.Stub() {

		@Override
		public void decodedPcmData(byte[] pcmData) throws RemoteException {
			if (pcmData != null && pcmData.length > 0) {
				for(RadioServiceAudiodataListener pcmListener : mAudioListeners) {
					pcmListener.pcmAudioData(pcmData, mConfChans, mConfSampling);
				}
			}
		}
	};


	/* ************************************************************* */

	void prepare(RadioService timeshiftService) throws IOException {
		mPlayWhenReady = false;

		mTimeshiftService = timeshiftService;

		mEncFile = File.createTempFile("dabtmp_" + System.currentTimeMillis(), ".dab", mCacheDir);
		mOutputPath = mEncFile.getAbsolutePath();
		mFileOutStream = new FileOutputStream(mEncFile);

		mPlayerPaused = false;
		mTimeshiftService.subscribe(this);
	}

	@Override
	public void play() {
		startPlayThread();
	}

	@Override
	public void setPlayWhenReady() {
		mPlayWhenReady = true;
	}

	@Override
	public void stop(boolean deleteTemp) {

		stopPlayThread();

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

		mPlayerPaused = false;
		mStartTimeNs = -1;
		mLastSlsPath = null;
		mLabelFileSparse.clear();
		mLabelFileSparse.clear();
		mSlideshowFileSparse.clear();

		if(deleteTemp) {
			TimeshiftUtils.deleteFolder(mCacheDir);
		}

		for(TimeshiftListener listener : mListeners) {
			if(listener != null) {
				listener.stopped();
			}
		}
	}

	@Override
	public void pause(boolean pause) {
		mPlayerPaused = pause;
	}

	@Override
	public boolean isPaused() {
		return mPlayerPaused;
	}

	@Override
	public void seek(long seekMilliseconds) {
		mSeekToPos = seekMilliseconds;
	}

	@Override
	public List<SkipItem> getSkipItems() {
		return mSkipItems;
	}

	@Override
	public void skipTo(SkipItem item) {
		if(item != null) {
			mSkipPos = item.getSkipPoint();
			mReadAus = item.getWrittenAus();
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

	@Override
	public long getDuration() {
		return mTimeshiftDuration;
	}

	@Override
	public long getCurrentPosition() {
		return mReadAus * mMsAudioPerAu;
	}

	@Override
	public void addListener(TimeshiftListener listener) {
		if(!mListeners.contains(listener)) {
			mListeners.add(listener);
		}
	}

	@Override
	public void removeListener(TimeshiftListener listener) {
		mListeners.remove(listener);
	}

	/* ***************************************************** */
	private void startPlayThread() {
		if(!mPlaythreadRunning) {
			try {
				mInFile = new RandomAccessFile(mEncFile, "r");
				mInFile.skipBytes(4);
			} catch(IOException ioE) {
				if(DEBUG) Log.e(TAG, "Error opening InFile");
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
		if(mPlaythreadRunning) {
			mPlaythreadRunning = false;
			if(mPlayThread != null) {
				if(mPlayThread.isAlive()) {
					mPlayThread.interrupt();
					try {
						mPlayThread.join();
					} catch(InterruptedException iErr) {
						if(DEBUG)iErr.printStackTrace();
					}
				}
			}
			mPlayThread = null;
		}
	}

	private Runnable PlayerRunnable = new Runnable() {
		@Override
		public void run() {
			mPlaythreadRunning = true;
			playTimeshift();
		}
	};

	private void playTimeshift() {
		do {
			try {
				//Seeking
				if(mSeekToPos >= 0) {
					seekToMs(mSeekToPos);
				}
				//Skip
				if(mSkipPos >= 0) {
					mInFile.seek(mSkipPos);
					mSkipPos = -1;
				}

				//Pause
				if(mPlayerPaused) {
					Thread.sleep(100);
					continue;
				}

				//Handle EOF
				if(mEofPause) {
					Thread.sleep(1000);
					continue;
				}

				//Play
				byte[] data = new byte[mMpegFrameSize];
				mInFile.read(data);
				++mReadAus;

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

				try {
					if(mDecoderService != null) {
						mDecoderService.enqueueEncodedData(data);
					}
				} catch(RemoteException remExc) {
					if(DEBUG)remExc.printStackTrace();
				}
			} catch(EOFException eofExc) {
				if(DEBUG)eofExc.printStackTrace();
				mEofPause = true;
			} catch(IOException ioE) {
				if(DEBUG)ioE.printStackTrace();
			} catch(InterruptedException interE) {
				if(DEBUG)interE.printStackTrace();
			}
		} while(mPlaythreadRunning);
	}

	private void seekToMs(long milliSecondsPos) {
		if(DEBUG) Log.d(TAG, "Seeking to: " + milliSecondsPos);

		//can't seek beyond the actual duration or too close to the end of cache file
		if(milliSecondsPos >= mTimeshiftDuration) {
			return;
		}

		try {
			long numAUsSeek = milliSecondsPos / mMsAudioPerAu;
			mReadAus = numAUsSeek;
			mInFile.seek(numAUsSeek*mMpegFrameSize);

			mInFile.getFD().sync();

			long seek = mInFile.getChannel().position();
			long maxSeek = mInFile.getChannel().size();

			int nextByte = mInFile.readUnsignedByte();

			//search audioframe start
			while (seek < maxSeek) {
				if(nextByte == 0xFF) {
					nextByte = mInFile.readUnsignedByte();

					if((nextByte & 0xF0) == 0xF0) {
						mInFile.seek(seek);
						mInFile.getFD().sync();
						mSeekToPos = -1;
						break;
					}
				}

				nextByte = mInFile.readUnsignedByte();
				++seek;
			}
		} catch(IOException iOE) {
			if(DEBUG)iOE.printStackTrace();
		}

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

	}
	/* ***************************************************** */

	/* DLS listener */
	@Override
	public void newTextualMetadata(Textual textual) {
		if(textual.getType() == TextualType.METADATA_TEXTUAL_TYPE_DAB_DLS) {
			if(DEBUG) Log.d(TAG, "New textual: " + textual.getText());
			TextualDabDynamicLabel dls = (TextualDabDynamicLabel)textual;

			long skipPoint = -1;
			try {
				skipPoint = mFileOutStream.getChannel().position();
			} catch(IOException ioExc) {
				if(DEBUG)ioExc.printStackTrace();
			}

			String dlsPathName = TimeshiftUtils.serializeTextual(textual, mCacheTextDir+"/"+mWrittenAus);
			if(dlsPathName != null) {
				mLabelFileSparse.append(mWrittenAus, dlsPathName);
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

						if (DEBUG) Log.d(TAG, "Adding SkipingPoint at: " + skipPoint);
					}

				}
			}
		}
	}

	/* SLS listener */
	@Override
	public void newVisualMetadata(Visual visual) {
		if(visual.getVisualType() == VisualType.METADATA_VISUAL_TYPE_DAB_SLS) {
			String visPath = TimeshiftUtils.serializeVisual(visual, mCacheVisDir+"/"+mWrittenAus);
			if(visPath != null) {
				mLastSlsPath = visPath;
				mSlideshowFileSparse.append(mWrittenAus, visPath);
			}
		}
	}

	/* RawAudio listener */
	@Override
	public void rawAudioData(byte[] rawData, boolean sbr, boolean ps, RadioServiceMimeType type, int numChannels, int samplingRate) {
		if(((rawData[0] & 0xFF) != 0xFF) && ((rawData[1] & 0xF0) != 0xF0) ) {
			if(DEBUG)Log.w(TAG, "Wrong MPEG sync");
			return;
		}

		//first audiopacket, configure everything
		if(mStartTimeNs == -1) {
			mStartTimeNs = System.nanoTime();
			mTimeshiftDuration = 0;
			mReadAus = -1;
			mLastDuration = 0;
			mWrittenAus = 0;
			mMsAudioPerAu = (samplingRate == 48000 ? 24 : 48);

			//DAB MPEG-1L2 has a constant frame size
			mMpegFrameSize = rawData.length;
		}

		if(mConfChans != numChannels || mConfSampling != samplingRate) {
			mConfChans = numChannels;
			mConfSampling = samplingRate;

		}

		mTimeshiftDuration += mMsAudioPerAu;

		try {
			mFileOutStream.write(rawData);

			//need to flush to have it ready on disk
			mFileOutStream.flush();
			++mWrittenAus;
		} catch(IOException ioE) {
			if(DEBUG)ioE.printStackTrace();
		}

		/* ... */
		long MILLI_PART = 1000;
		if((mTimeshiftDuration/MILLI_PART) > mLastDuration) {
			mLastDuration = (mTimeshiftDuration/MILLI_PART);

			for(TimeshiftListener listener : mListeners) {
				if(listener != null) {
					listener.progress(((mReadAus * mMsAudioPerAu) / MILLI_PART), (mTimeshiftDuration / MILLI_PART));
				}
			}

			if(mEofPause) {
				mEofPause = false;
			}

			//Start playback if playwhenReady was set and we have enough data buffered
			if(mPlayWhenReady && !mPlaythreadRunning) {
				if(((mTimeshiftDuration/MILLI_PART)) >= 2) {
					play();
				}
			}

		}
	}
}
