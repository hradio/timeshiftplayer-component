# Timeshift Player Library

This is an Android library project for a HRadio timeshift player component.

## Compile

You need the Android SDK and NDK installed.
Import project into Android Studio or use the ./gradlew or gradlew.bat 
command line tools to compile the library.

## Usage

The timeshift is saved as as temporary file in the Apps cache directory.
Usually it's no problem. But if the device runs low on space it will clean up
those directories and could delete the timeshift file. Keep that in mind. 

Create a new `TimeshiftPlayer` with the App Context and a running `RadioService` 
with `TimeshiftPlayerFactory.create(appContext, service)`  
Add a `TimeshiftListener` with `addListener(TimeshiftListener listener)` 
if you are interested in status updates.  
If you want the player to start immediately after enough data is in
the buffer call `setPlayWhenReady()`. Otherwise call `play()` when
you're ready.  
Use `pause(boolean pause)` method to pause and unpause the player.  
Call `seek(long seekMilliseconds)` with the desired playback position
in milliseconds to seek in the timeshift.  
When you're done with timeshifting or if you want to timeshift an 
other RadioService you should call `stop()` before creating a new one.

### Problems

You tell...open an issue
