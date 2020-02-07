package eu.hradio.timeshiftplayer;

import android.util.Log;

import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceAudiodataListener;
import org.omri.radioservice.RadioServiceDabEdi;
import org.omri.radioservice.metadata.SbtItem;
import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.TextualMetadataListener;
import org.omri.radioservice.metadata.Visual;
import org.omri.radioservice.metadata.VisualMetadataListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
public class TimeshiftPlayerEdiSbt implements TimeshiftPlayer, RadioServiceDabEdi.SbtCallback, TextualMetadataListener, VisualMetadataListener {

	private final static String TAG = "TimeshiftPlayerEdiSbt";

	private boolean mPlayWhenReady = false;
	private boolean mPlayerPaused = false;
	private RadioServiceDabEdi mTimeshiftService = null;

	private long mSbtMaxMs = 0;
	private List<SkipItem> mSkipItems = new ArrayList<>();
	private long mNowPos = 0;

	private CopyOnWriteArrayList<TimeshiftListener> mListeners = new CopyOnWriteArrayList<>();

	@Override
	public void play() {
		if(BuildConfig.DEBUG)Log.d(TAG, "play()");
		for(TimeshiftListener listener : mListeners) {
			listener.started();
		}
	}

	@Override
	public void setPlayWhenReady() {
		if(BuildConfig.DEBUG)Log.d(TAG, "setPlayWhenReady()");
		mPlayWhenReady = true;
		for(TimeshiftListener listener : mListeners) {
			listener.started();
		}
	}

	@Override
	public void stop(boolean deleteTemp) {
		if(BuildConfig.DEBUG)Log.d(TAG, "stop()");
		for(TimeshiftListener listener : mListeners) {
			listener.stopped();
		}
	}

	@Override
	public void pause(boolean pause) {
		if(BuildConfig.DEBUG)Log.d(TAG, "pause()");
		mPlayerPaused = pause;
		if(mTimeshiftService != null) {
			mTimeshiftService.pauseSbt(pause);
			if(mPlayerPaused) {
				for (TimeshiftListener listener : mListeners) {
					listener.paused();
				}
			} else {
				for (TimeshiftListener listener : mListeners) {
					listener.started();
				}
			}
		}
	}

	@Override
	public boolean isPaused() {
		if(BuildConfig.DEBUG)Log.d(TAG, "isPaused() " + mPlayerPaused);
		return mPlayerPaused;
	}

	@Override
	public void seek(long seekMilliseconds) {
		if(BuildConfig.DEBUG) Log.d(TAG, "SBT Seeking to: " + seekMilliseconds + " : " + (mSbtMaxMs-seekMilliseconds));
		if(seekMilliseconds <= mSbtMaxMs) {
			mTimeshiftService.seekSbt(mSbtMaxMs-seekMilliseconds);
		}
	}

	@Override
	public List<SkipItem> getSkipItems() {
		//if(BuildConfig.DEBUG)Log.d(TAG, "getSkipItems() : " + mSkipItems.size());
		return mSkipItems;
	}

	@Override
	public void skipTo(SkipItem item) {
		if(BuildConfig.DEBUG) Log.d(TAG, "SBT skipTo: " + item.getSkipPoint());
		mTimeshiftService.setToggleSbt(item.getSkipPoint());
		if(item.getSkipVisual() != null) {
			for (TimeshiftListener listener : mListeners) {
				listener.visual(item.getSkipVisual());
				listener.textual(item.getSkipTextual());
			}
		}
	}

	@Override
	public long getCurrentPosition() {
		if(BuildConfig.DEBUG)Log.d(TAG, "getCurrentPosition() : " + mNowPos);
		return mNowPos;
	}

	@Override
	public long getDuration() {
		if(BuildConfig.DEBUG)Log.d(TAG, "getDuration() : " + mSbtMaxMs);
		return mSbtMaxMs;
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

	@Override
	public void addAudioDataListener(RadioServiceAudiodataListener pcmListener) {
		if(mTimeshiftService != null) {
			mTimeshiftService.subscribe(pcmListener);
		}
	}

	@Override
	public void removeAudioDataListener(RadioServiceAudiodataListener pcmListener) {
		if(mTimeshiftService != null) {
			mTimeshiftService.unsubscribe(pcmListener);
		}
	}

	@Override
	public RadioService getRadioService() {
		return mTimeshiftService;
	}

	void prepare(RadioServiceDabEdi timeshiftService) {
		mTimeshiftService = timeshiftService;
		mSbtMaxMs = mTimeshiftService.getSbtMax();
		mTimeshiftService.addSbtCallback(this);
		mTimeshiftService.subscribe(this);

		for(SbtItem item : mTimeshiftService.getSbtItems()) {
			long skipRelDiff = mTimeshiftService.getRealtimePosixMs() - item.getPosixTime();
			long skipReltime = mSbtMaxMs - skipRelDiff;

			mSkipItems.add(new SkipItem(skipReltime, item.getPosixTime(), item.getId(), item.getDls(), item.getSls()));
 		}

		if(BuildConfig.DEBUG) Log.d(TAG, "SBT Created " + mSkipItems.size() + " SkipItems");
	}

	/* SBT callbacks */
	@Override
	public void sbtEnabled() {
		if(BuildConfig.DEBUG) Log.d(TAG, "SBT sbtEnabled");
	}

	@Override
	public void streamDabTime(long posixMilliSeconds) {
		long ntpMs = mTimeshiftService.getRealtimePosixMs();
		long nowDabDiff = ntpMs - posixMilliSeconds;
		long mNowPos = mSbtMaxMs - nowDabDiff;

		for(TimeshiftListener listener : mListeners) {
			listener.sbtRealTime(ntpMs, posixMilliSeconds, mNowPos/1000, mSbtMaxMs/1000);
		}
	}

	@Override
	public void sbtItemAdded(SbtItem sbtItem) {
		if(BuildConfig.DEBUG) Log.d(TAG, "SBT sbtItemAdded: " + sbtItem.getDls().getText());

		long skipRelDiff = mTimeshiftService.getRealtimePosixMs() - sbtItem.getPosixTime();
		long skipReltime = mSbtMaxMs - skipRelDiff;

		SkipItem newItem = new SkipItem(skipReltime, sbtItem.getPosixTime(), sbtItem.getId(), sbtItem.getDls(), sbtItem.getSls());
		mSkipItems.add(newItem);

		for(TimeshiftListener listener : mListeners) {
			listener.skipItemAdded(newItem);
		}
	}

	@Override
	public void sbtItemInvalid(SbtItem sbtItem) {
		if(BuildConfig.DEBUG) Log.d(TAG, "SBT sbtItemInvalid: " + new Date(sbtItem.getPosixTime()).toString() + " : " + sbtItem.getDls().getText());

		Iterator<SkipItem> skipIter = mSkipItems.iterator();
		while (skipIter.hasNext()) {
			SkipItem nextItem = skipIter.next();
			if(nextItem.getSbtRealTime() == sbtItem.getPosixTime()) {
				for(TimeshiftListener listener : mListeners) {
					listener.skipItemRemoved(nextItem);
				}

				skipIter.remove();
				break;
			}
		}
	}


	/* RadioService Metadata */
	@Override
	public void newVisualMetadata(Visual visual) {
		for(TimeshiftListener listener : mListeners) {
			listener.visual(visual);
		}
	}

	@Override
	public void newTextualMetadata(Textual textual) {
		for(TimeshiftListener listener : mListeners) {
			listener.textual(textual);
		}
	}
}
