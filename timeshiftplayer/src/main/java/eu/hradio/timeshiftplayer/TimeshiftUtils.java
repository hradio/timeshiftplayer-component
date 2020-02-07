package eu.hradio.timeshiftplayer;

import android.util.Log;

import org.omri.radioservice.metadata.Textual;
import org.omri.radioservice.metadata.Visual;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
class TimeshiftUtils {

	private static final String TAG = "TimeshiftUtils";

	static String serializeVisual(Visual vis, String pathName) {
		try {
			FileOutputStream visObjFileOut = new FileOutputStream(pathName);
			ObjectOutputStream visObjOut = new ObjectOutputStream(visObjFileOut);
			visObjOut.writeObject(vis);
			visObjOut.close();
			visObjFileOut.close();

			return pathName;
		} catch (IOException ioE) {
			if(BuildConfig.DEBUG)ioE.printStackTrace();
		}

		return null;
	}

	static Visual deserializeVisual(String name) {
		if(name == null) {
			return null;
		}

		try {
			FileInputStream visObjFileIn = new FileInputStream(name);
			ObjectInputStream visObjIn = new ObjectInputStream(visObjFileIn);
			Visual restoredVis = (Visual) visObjIn.readObject();

			visObjFileIn.close();
			visObjIn.close();

			return restoredVis;
		} catch(IOException | ClassNotFoundException exception) {
			if(BuildConfig.DEBUG)exception.printStackTrace();
		}

		return null;
	}

	static String serializeTextual(Textual text, String pathName) {
		try {
			FileOutputStream textObjFileOut = new FileOutputStream(pathName);
			ObjectOutputStream textObjOut = new ObjectOutputStream(textObjFileOut);
			textObjOut.writeObject(text);
			textObjOut.close();
			textObjFileOut.close();

			return pathName;
		} catch (IOException ioE) {
			if(BuildConfig.DEBUG)ioE.printStackTrace();
		}

		return null;
	}

	static Textual deserializeTextual(String name) {
		if(name == null) {
			return null;
		}

		try {
			FileInputStream textObjFileIn = new FileInputStream(name);
			ObjectInputStream textObjIn = new ObjectInputStream(textObjFileIn);
			Textual restoredText = (Textual) textObjIn.readObject();

			textObjFileIn.close();
			textObjIn.close();

			return restoredText;
		} catch(IOException | ClassNotFoundException exception) {
			if(BuildConfig.DEBUG)exception.printStackTrace();
		}

		return null;
	}

	static void deleteFolder(final File folder) {
		if (folder != null) {
			ExecutorService executor = Executors.newSingleThreadExecutor();

			executor.execute(new Runnable() {
				@Override
				public void run() {
					File[] files = folder.listFiles();
					if (files != null) { //some JVMs return null for empty dirs
						for (File f : files) {
							if (f.isDirectory()) {
								deleteFolder(f);
							} else {
								boolean deleted = f.delete();
								if(BuildConfig.DEBUG)Log.d(TAG, "Deleted Timeshift-file: " + deleted);
							}
						}
					}
					folder.delete();

					if (BuildConfig.DEBUG)
						Log.d(TAG, "Deleting temporary timeshift folder finished");
				}
			});
		}
	}
}
