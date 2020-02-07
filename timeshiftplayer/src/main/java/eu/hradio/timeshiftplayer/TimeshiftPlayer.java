package eu.hradio.timeshiftplayer;

import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceAudiodataListener;

import java.io.Serializable;
import java.util.List;

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
public interface TimeshiftPlayer extends Serializable {

	/**
	 * Starts the timeshift playback. You can call this only once. Use pause toggle to pause/unpause playback
	 */
	void play();

	/**
	 * Call it when you wish that playback starts immediately without calling play()
	 */
	void setPlayWhenReady();

	/**
	 * Stops the {@link TimeshiftPlayer}
	 * @param deleteTemp {@code true} if you want to delete the previously created timeshift file, {@code false} otherwise
	 */
	void stop(boolean deleteTemp);

	/**
	 * Pauses or resumes the player
	 * @param pause the wanted state: {@code true} for pause, {@code false} for resume
	 */
	void pause(boolean pause);

	/**
	 * Indicates if the {@link TimeshiftPlayer} is currently paused
	 * @return {@code true} if the {@link TimeshiftPlayerAacAu} is paused, {@code false} otherwise
	 */
	boolean isPaused();

	/**
	 * Seeks to the given position
	 * @param seekMilliseconds the wanted position in milliseconds
	 */
	void seek(long seekMilliseconds);

	/**
	 * Returns a list of {@link SkipItem}s or an empty list
	 * @return a list of {@link SkipItem}s or an empty list
	 */
	List<SkipItem> getSkipItems();

	/**
	 * Skips to the {@link SkipItem}s state
	 * @param item the {@link SkipItem} to skip to
	 */
	void skipTo(SkipItem item);

	/**
	 * Returns the current position in milliseconds from the beginning
	 * @return the current position in milliseconds from the beginning
	 */
	long getCurrentPosition();

	/**
	 * Returns the current total timeshifted duration in milliseconds
	 * @return the current total timeshifted duration in milliseconds
	 */
	long getDuration();

	/**
	 * Adds a {@link TimeshiftListener} to receive status updates
	 * @param listener {@link TimeshiftListener} to add
	 */
	void addListener(TimeshiftListener listener);

	/**
	 * Removes a {@link TimeshiftListener}
	 * @param listener {@link TimeshiftListener} to remove
	 */
	void removeListener(TimeshiftListener listener);

	/**
	 * Add a {@link RadioServiceAudiodataListener} to the list of listeners
	 * @param pcmListener the listener to add
	 */
	void addAudioDataListener(RadioServiceAudiodataListener pcmListener);

	/**
	 * Remove a previously added {@link RadioServiceAudiodataListener} from the list of listeners
	 * @param pcmListener the listener to remove
	 */
	void removeAudioDataListener(RadioServiceAudiodataListener pcmListener);

	/**
	 * Returns the currently running {@link RadioService}
	 * @return the currently running {@link RadioService} or <em>null</em> if none is running
	 */
	RadioService getRadioService();
}
