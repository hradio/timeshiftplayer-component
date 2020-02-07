package eu.hradio.timeshiftplayer;

import android.content.Context;
import android.util.Log;

import org.omri.radioservice.RadioService;
import org.omri.radioservice.RadioServiceDab;
import org.omri.radioservice.RadioServiceDabComponent;
import org.omri.radioservice.RadioServiceDabEdi;
import org.omri.radioservice.RadioServiceMimeType;

import java.io.IOException;

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
public final class TimeshiftPlayerFactory {

	private final static String TAG = "TimeshiftPlayerFactory";

	/**
	 * {@link TimeshiftPlayer} factory method
	 * @param context the applications context
	 * @param service the service
	 * @return a {@link TimeshiftPlayer} for the given {@link RadioService} or {@code null} if no {@link TimeshiftPlayer} is available for this {@link org.omri.radioservice.RadioServiceType}
	 * @throws IOException if the creation of the (temporary) recording file fails
	 */
	public static TimeshiftPlayer create(final Context context, final RadioService service) throws IOException {
		if(context != null && service != null) {
			switch (service.getRadioServiceType()) {
				case RADIOSERVICE_TYPE_DAB: {
					RadioServiceDab dabSrv = (RadioServiceDab)service;
					for(RadioServiceDabComponent component : dabSrv.getServiceComponents()) {
						if(component.isPrimary()) {
							if(component.getServiceComponentType() == RadioServiceMimeType.AUDIO_AAC_DAB_AU.getContententTypeId()) {
								if(BuildConfig.DEBUG) Log.d(TAG, "Creating AAC AU TimeshiftPlayer");

								TimeshiftPlayerAacAu tsPlayer = null;
								if (android.os.Build.VERSION.SDK_INT >= 21) {
									if(BuildConfig.DEBUG) Log.d(TAG, "Creating SDK_21 Player");
									tsPlayer = new TimeshiftPlayerAacAuSdk21(context);
								} else {
									if(BuildConfig.DEBUG) Log.d(TAG, "Creating SDK_16 Player");
									tsPlayer = new TimeshiftPlayerAacAuSdk16(context);
								}

								tsPlayer.prepare(service);

								return tsPlayer;
							} else if(component.getServiceComponentType() == RadioServiceMimeType.AUDIO_MPEG.getContententTypeId()) {
								if(BuildConfig.DEBUG) Log.d(TAG, "Creating MPEG TimeshiftPlayer");

								TimeshiftPlayerMpeg2 tsPlayer = new TimeshiftPlayerMpeg2(context);
								tsPlayer.prepare(service);

								return tsPlayer;
							}
						}
					}

					break;
				}
				case RADIOSERVICE_TYPE_EDI: {
					RadioServiceDabEdi ediSrv = (RadioServiceDabEdi) service;
					if(ediSrv.sbtEnabled()) {
						if (BuildConfig.DEBUG) Log.d(TAG, "Creating EDI SBT player");
						TimeshiftPlayerEdiSbt ediSbtPlayer = new TimeshiftPlayerEdiSbt();
						ediSbtPlayer.prepare(ediSrv);

						return ediSbtPlayer;
					} else {
						for (RadioServiceDabComponent component : ediSrv.getServiceComponents()) {
							if (component.isPrimary()) {
								if (component.getServiceComponentType() == RadioServiceMimeType.AUDIO_AAC_DAB_AU.getContententTypeId()) {
									if (BuildConfig.DEBUG) Log.d(TAG, "Creating AAC AU TimeshiftPlayer");

									TimeshiftPlayerAacAu tsPlayer = null;
									if (android.os.Build.VERSION.SDK_INT >= 21) {
										if (BuildConfig.DEBUG) Log.d(TAG, "Creating SDK_21 Player");
										tsPlayer = new TimeshiftPlayerAacAuSdk21(context);
									} else {
										if (BuildConfig.DEBUG) Log.d(TAG, "Creating SDK_16 Player");
										tsPlayer = new TimeshiftPlayerAacAuSdk16(context);
									}

									tsPlayer.prepare(service);

									return tsPlayer;
								} else if (component.getServiceComponentType() == RadioServiceMimeType.AUDIO_MPEG.getContententTypeId()) {
									if (BuildConfig.DEBUG) Log.d(TAG, "Creating MPEG TimeshiftPlayer");

									TimeshiftPlayerMpeg2 tsPlayer = new TimeshiftPlayerMpeg2(context);
									tsPlayer.prepare(service);

									return tsPlayer;
								}
							}
						}
					}
					break;
				}
				case RADIOSERVICE_TYPE_IP: {
					break;
				}
			}
		}

		return null;
	}

	public static TimeshiftPlayerPcmAu createPcmPlayer(final Context context, final RadioService service) throws IOException {
		TimeshiftPlayerPcmAu retPlayer = null;

		if(context != null && service != null) {
			if (android.os.Build.VERSION.SDK_INT >= 21) {
				retPlayer = new TimeshiftPlayerPcmAuSdk21(context);
				((TimeshiftPlayerPcmAuSdk21)retPlayer).prepare(service);
			} else {
				retPlayer = new TimeshiftPlayerPcmAuSdk16(context);
				((TimeshiftPlayerPcmAuSdk16)retPlayer).prepare(service);
			}
		}

		return retPlayer;
	}
}
