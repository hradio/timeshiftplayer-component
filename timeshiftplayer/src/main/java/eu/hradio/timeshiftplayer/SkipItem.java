package eu.hradio.timeshiftplayer;

import android.util.Log;

import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.Visual;

import java.io.Serializable;

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
public class SkipItem implements Serializable {

	private static final long serialVersionUID = -2914224179187786533L;

	private final String mSkipDlsPathName;
	private String mSkipVisPathName = null;
	private final long mWrittenAusSoFar;
	private final long mSkipPoint;
	private final long mRelTimepoint;

	//SBT addition
	private final boolean mIsSbtItem;
	private final Visual mSbtVisual;
	private final Textual mSbtTextual;
	private final long mSbtRealTime;

	SkipItem(long numAus, long skipPoint, long relativeTimepoint, String skipDlsPath) {
		if(DEBUG) Log.d("SkipItem", "Creating at Path: " + skipDlsPath);
		mIsSbtItem = false;
		mSbtVisual = null;
		mSbtTextual = null;
		mSbtRealTime = 0;

		mWrittenAusSoFar = numAus;
		mSkipPoint = skipPoint;
		//mSkipDls = skipDls;
		mSkipDlsPathName = skipDlsPath;
		mRelTimepoint = relativeTimepoint;
	}

	SkipItem(long relativeTimepoint, long sbtRealTime, long toggleId, Textual dls, Visual sls) {
		mIsSbtItem = true;
		mRelTimepoint = relativeTimepoint;
		mSbtTextual = dls;
		mSbtVisual = sls;
		mSkipPoint = toggleId;
		mSbtRealTime = sbtRealTime;

		mWrittenAusSoFar = 0;
		mSkipDlsPathName = "";
	}

	void setSkipSls(String skipVisPathName) {
		mSkipVisPathName = skipVisPathName;
	}

	/**
	 * Returns the number of previous saved AUs
	 * @return the number of previous saved AUs
	 */
	public long getWrittenAus() {
		return mWrittenAusSoFar;
	}

	/**
	 * Returns the skip point
	 * @return the skip point
	 */
	public long getSkipPoint() {
		return mSkipPoint;
	}

	/**
	 * Returns the time point relative to the start timepoint in milliseconds
	 * @return the time point relative to the start timepoint in milliseconds
	 */
	public long getRelativeTimepoint() {
		return mRelTimepoint;
	}

	/**
	 * Returns the Textual at the point of the skip item
	 * @return the Textual at the point of the skip item
	 */
	public Textual getSkipTextual() {
		if(!mIsSbtItem) {
			return TimeshiftUtils.deserializeTextual(mSkipDlsPathName);
		} else {
			return mSbtTextual;
		}
	}

	/**
	 * Returns a Visual at the point of the skip item or {@code null} if not available
	 * @return a Visual at the point of the skip item or {@code null} if not available
	 */
	public Visual getSkipVisual() {
		if(!mIsSbtItem) {
			return TimeshiftUtils.deserializeVisual(mSkipVisPathName);
		} else {
			return mSbtVisual;
		}
	}

	public long getSbtRealTime() {
		return mSbtRealTime;
	}
}
