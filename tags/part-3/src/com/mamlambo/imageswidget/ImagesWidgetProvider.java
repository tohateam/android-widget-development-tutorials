package com.mamlambo.imageswidget;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Vector;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.RemoteViews;

/**
 * @author Shane Conder and Lauren Darcey
 * 
 */
public class ImagesWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_WIDGET_CONTROL = "com.mamlambo.ImagesWidget.WIDGET_CONTROL";
    private static final String LOG_TAG = "ImagesWidgetProvider";

    public static final String URI_SCHEME = "images_widget";

    @Override
    public void onEnabled(Context context) {
        // This is only called once, regardless of the number of widgets of this type
        Log.i(LOG_TAG, "onEnabled()");

        super.onEnabled(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Log.d(LOG_TAG, "onUpdate(): ");

        for (int appWidgetId : appWidgetIds) {

            try {
                WidgetState state = WidgetState.getState(context, appWidgetId);

                // update the Service, which triggers an updateWidget() call....
                if (state.feedUrl.length() > 0) {
                    updateWidgetViaService(context, appWidgetId, !state.paused, false);
                }

            } catch (Exception e) {
                // don't want one widget failure to interrupt the rest
                Log.e(LOG_TAG, "Failed updating: " + appWidgetId, e);
            }

        }
        // super.onUpdate(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        Log.d(LOG_TAG, "onDelete()");

        for (int appWidgetId : appWidgetIds) {

            // stop alarm
            setAlarm(context, appWidgetId, -1);

            // remove preference
            Log.d(LOG_TAG, "Removing preference for id " + appWidgetId);

            // remove our stored state
            WidgetState.deleteStateForId(context, appWidgetId);

            // update service
            updateWidgetViaService(context, appWidgetId, false, true);
        }

        super.onDeleted(context, appWidgetIds);
    }

    // Fix SDK 1.5 Bug per note here:
    // http://developer.android.com/guide/topics/appwidgets/index.html#AppWidgetProvider
    // linking to this post:
    // http://groups.google.com/group/android-developers/msg/e405ca19df2170e2
    @Override
    public void onReceive(Context context, Intent intent) {

        final String action = intent.getAction();
        Log.d(LOG_TAG, "OnReceive:Action: " + action);
        if (AppWidgetManager.ACTION_APPWIDGET_DELETED.equals(action)) {
            final int appWidgetId = intent.getExtras().getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                this.onDeleted(context, new int[] { appWidgetId });
            }
        } else if (ACTION_WIDGET_CONTROL.equals(action)) {
            // pass this on to the action handler where we'll figure out what to do and update the widget
            final int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {

                this.onHandleAction(context, appWidgetId, intent.getData());
            }

        } else if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {

            if (!URI_SCHEME.equals(intent.getScheme())) {
                // if the scheme doesn't match, that means it wasn't from the alarm
                // either it's the first time in (even before the configuration
                // is done) or after a reboot or update

                final int[] appWidgetIds = intent.getExtras().getIntArray(AppWidgetManager.EXTRA_APPWIDGET_IDS);

                for (int appWidgetId : appWidgetIds) {

                    // get current state
                    WidgetState state = WidgetState.getState(context, appWidgetId);

                    // only start the alarm if it's not paused -- FIXed after article submitted
                    if (state.updateRateSeconds != -1 && !state.paused) {
                        Log.i(LOG_TAG, "Starting recurring alarm for id " + appWidgetId);

                        setAlarm(context, appWidgetId, state.updateRateSeconds);
                    }
                }
            }
            super.onReceive(context, intent);
        } else {
            super.onReceive(context, intent);
        }
    }

    /**
     * Helper method to handle action Intents posted back to this handler. Triggered by a user.
     * 
     * @param context
     *            The context object
     * @param appWidgetId
     *            The widget this action applies to
     * @param data
     *            {@code Uri} representing the action to take
     */
    private void onHandleAction(Context context, int appWidgetId, Uri data) {
        String controlType = data.getFragment();
        WidgetState state = WidgetState.getState(context, appWidgetId);

        // if we're just changing the image, the state doesn't change. just notify the service.
        if (controlType.equalsIgnoreCase("next")) {
            updateWidgetViaService(context, appWidgetId, true, false);
        } else {
            // actions -- simply update the state
            if (controlType.equalsIgnoreCase("active")) {
                state.controlsActive = (state.controlsActive ? false : true);
            } else if (controlType.equalsIgnoreCase("playpause")) {
                state.paused = (state.paused ? false : true);

                if (state.paused) {
                    setAlarm(context, appWidgetId, -1);
                } else {
                    setAlarm(context, appWidgetId, state.updateRateSeconds);
                }
            }

            // save the new state of things
            WidgetState.storeState(context, appWidgetId, state);

            // update the widget
            updateWidgetViaService(context, appWidgetId, false, false);
        }
    }

    /**
     * Helper method to start a server -- or send an Intent to a current running service Used to update the App Widget in its host.
     * 
     * @param context
     *            Context to use for this action
     * @param appWidgetId
     *            The App Widget identifier to update
     * @param updateImage
     *            True to change the current image, false to use the same one
     * @param requestStop
     *            True to stop the widget service thread
     */
    private void updateWidgetViaService(Context context, int appWidgetId, boolean updateImage, boolean requestStop) {
        Intent intent = new Intent(context, WidgetService.class);

        intent.putExtra(WidgetService.EXTRA_FLAG_REQUEST_STOP, requestStop);
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent.putExtra(WidgetService.EXTRA_FLAG_UPDATE_IMAGE, updateImage);

        context.startService(intent);
    }

    /**
     * Sets an alarm to post a broadcast pending intent for updating a particular appWidgetId
     * 
     * @param context
     *            the Context to set the alarm under
     * @param appWidgetId
     *            the widget identifier for this alarm
     * @param updateRateSeconds
     *            the amount of time between alarms
     */
    private void setAlarm(Context context, int appWidgetId, int updateRateSeconds) {
        Intent widgetUpdate = new Intent();
        widgetUpdate.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        widgetUpdate.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { appWidgetId });

        // make this pending intent unique by adding a scheme to it
        widgetUpdate.setData(Uri.withAppendedPath(Uri.parse(ImagesWidgetProvider.URI_SCHEME + "://widget/id/"), String.valueOf(appWidgetId)));
        PendingIntent newPending = PendingIntent.getBroadcast(context, 0, widgetUpdate, PendingIntent.FLAG_UPDATE_CURRENT);

        // schedule the updating
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (updateRateSeconds >= 0) {
            alarms.setRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), updateRateSeconds * 1000, newPending);
        } else {
            alarms.cancel(newPending);
        }
    }

    public static class WidgetService extends Service {
        public static final String EXTRA_FLAG_REQUEST_STOP = "requestStop";
        public static final String EXTRA_FLAG_UPDATE_IMAGE = "flagUpdateImage";
        Hashtable<Integer, UpdateThread> threadPool = new Hashtable<Integer, UpdateThread>();

        @Override
        public void onStart(Intent intent, int startId) {
            // 
            super.onStart(intent, startId);

            int appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);

            boolean updateImage = intent.getBooleanExtra(WidgetService.EXTRA_FLAG_UPDATE_IMAGE, false);
            boolean stopRequested = intent.getBooleanExtra(WidgetService.EXTRA_FLAG_REQUEST_STOP, false);

            if (threadPool.containsKey(appWidgetId)) {
                UpdateThread thread = threadPool.get(appWidgetId);
                if (thread.isAlive()) {

                    if (stopRequested) {

                        thread.requestStop();
                        thread.clearImages();

                        if (threadPool.isEmpty()) {
                            // if there are no threads, we don't need to be running
                            this.stopSelf();
                        }
                    } else {
                        String imageUrl;
                        if (updateImage) {
                            imageUrl = thread.getNextImagePath(true);
                        } else {
                            imageUrl = thread.getCurrentImagePath();
                        }

                        updateWidget(this, appWidgetId, imageUrl);

                    }
                } else {
                    Log.w(LOG_TAG, "Thread has died: " + appWidgetId);
                }
            } else {
                UpdateThread thread = new UpdateThread(appWidgetId);
                threadPool.put(appWidgetId, thread);
                thread.start();

                // the widget has just gotten started, so nothing is displaying just yet
                // if we just return, nothing will display until the next update.
                // instead, we can try to wait for the first image to download
                // and then draw it...
                // TODO: There are better ways to do this.
                synchronized (this) {
                    try {

                        wait(5000);
                    } catch (InterruptedException e) {
                    }
                }
                String imageUrl = thread.getNextImagePath(true);
                updateWidget(this, appWidgetId, imageUrl);
            }
        }

        @Override
        public IBinder onBind(Intent intent) {
            // no binder here
            // an Intent receiver object can NOT bind.
            return null;
        }

 

        /**
         * Helper method to modify the controls displayed in the widget and set their respective PendingIntents
         * 
         * @param context
         *            The context to operate under
         * @param remoteView
         *            the {@code RemoteView} to modify
         * @param state
         *            The WidgetState object to use
         * @param appWidgetId
         *            The particular widget to act on
         */
        private void updateControlStateOfWidget(Context context, RemoteViews remoteView, WidgetState state, int appWidgetId) {
            if (state.controlsActive) {
                remoteView.setViewVisibility(R.id.controls_frame, View.VISIBLE);
                remoteView.setOnClickPendingIntent(R.id.play_pause, makeControlPendingIntent(context, "playpause", appWidgetId));
                remoteView.setOnClickPendingIntent(R.id.next, makeControlPendingIntent(context, "next", appWidgetId));

                Intent configIntent = new Intent(context, ImagesWidgetConfiguration.class);
                configIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                // gotta make this unique for this appwidgetid
                configIntent.setData(Uri.withAppendedPath(Uri.parse(ImagesWidgetProvider.URI_SCHEME + "://widget/id/"), String.valueOf(appWidgetId)));
                PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, configIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                remoteView.setOnClickPendingIntent(R.id.config, pendingIntent);
                if (state.paused) {
                    remoteView.setImageViewResource(R.id.play_pause, R.drawable.ic_menu_play_clip);
                } else {
                    remoteView.setImageViewResource(R.id.play_pause, R.drawable.ic_menu_stop);
                }
            } else {
                remoteView.setViewVisibility(R.id.controls_frame, View.GONE);
            }

            remoteView.setOnClickPendingIntent(R.id.widget_frame, makeControlPendingIntent(context, "active", appWidgetId));
            try {
                AppWidgetManager.getInstance(context).updateAppWidget(appWidgetId, remoteView); 
            } catch (Exception e) {
                Log.e(LOG_TAG, "Failure updating widget");
            }
        }

        /**
         * Call to update the widget in its host
         * 
         * @param context
         *            Context object to operate under
         * @param appWidgetId
         *            The App Widget identifier to update
         * @param imagePath
         *            Path to the image to put in the widget
         */
        private void updateWidget(Context context, int appWidgetId, String imagePath) {
            WidgetState state = WidgetState.getState(context, appWidgetId);

            RemoteViews remoteView = new RemoteViews(context.getPackageName(), R.layout.widget);

            if (imagePath != null) {
                Bitmap image = BitmapFactory.decodeFile(imagePath);
                remoteView.setImageViewBitmap(R.id.image, image);
            }

            // modify remoteView based on current state
            updateControlStateOfWidget(context, remoteView, state, appWidgetId);
        }

        /**
         * Helper method to create a new {@code PendingIntent} for a widget control action
         * 
         * @param context
         *            Context to create under
         * @param command
         *            Supported command string
         * @param appWidgetId
         *            The Widget ID for this command
         * @return a new Broadcast PendingIntent
         */
        private PendingIntent makeControlPendingIntent(Context context, String command, int appWidgetId) {
            Intent active = new Intent();
            active.setAction(ACTION_WIDGET_CONTROL);
            active.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            Uri data = Uri.withAppendedPath(Uri.parse(URI_SCHEME + "://widget/id/#" + command), String.valueOf(appWidgetId));
            active.setData(data);
            return (PendingIntent.getBroadcast(context, 0, active, PendingIntent.FLAG_ONE_SHOT));
        }

        private class UpdateThread extends Thread {
            // Update images from feed each hour
            private static final int URL_UPDATE_DELAY = 2 * 60 * 60 * 1000;
            private int appWidgetId;
            private boolean stopRequested = false;

            // Vector is synchronized for us.
            private Vector<String> imagePaths;
            private int curIndex;

            /**
             * Constructor for our custom Thread object to update the images on a schedule
             * 
             * @param appWidgetId
             *            The App Widget identifier this Thread is tied to
             */
            public UpdateThread(int appWidgetId) {
                super("ImagesWidgetThread");
                this.appWidgetId = appWidgetId;
                imagePaths = new Vector<String>(20);
                curIndex = 0;
            }

            /*
             * (non-Javadoc)
             * 
             * @see java.lang.Thread#run()
             */
            public void run() {
                long millis = 0;
                long millisOffset = 0;
                WidgetState state = WidgetState.getState(getApplicationContext(), appWidgetId);

                while (!stopRequested) {
                    synchronized (this) {
                        Log.d(LOG_TAG, "Test: appWidgetId=[" + appWidgetId + "], feedUrl=[" + state.feedUrl + "]");
                        // make sure it's been long enough
                        millisOffset = System.currentTimeMillis() - millis;
                        if (millisOffset > URL_UPDATE_DELAY) {
                            Log.i(LOG_TAG, "Updating Url: " + appWidgetId + "], feedUrl=[" + state.feedUrl + "]");
                            getAndParseFeedUrl(state.feedUrl);
                            millis = System.currentTimeMillis();
                            millisOffset = 0;
                        }
                        try {
                            wait(URL_UPDATE_DELAY - millisOffset);
                        } catch (InterruptedException e) {
                            Log.e(LOG_TAG, "Interrupted: " + e.toString(), e);
                        }
                        // update the state
                        state = WidgetState.getState(getApplicationContext(), appWidgetId);
                    }
                }
            }

            /**
             * Call to request this thread cleanly finish and stop
             * 
             * Waits until the thread has stopped -- or an Exception is triggered
             * 
             */
            public void requestStop() {
                synchronized (this) {
                    stopRequested = true;
                    notify();
                }
                try {
                    join();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }

            }

            public void clearImages() {
                Iterator<String> iterate = imagePaths.iterator();

                while (iterate.hasNext()) {
                    String path = iterate.next();

                    File image = new File(path);
                    boolean result = image.delete();
                    if (!result) {
                        Log.w(LOG_TAG, "Failed to delete: " + path);
                    }
                }
            }

            /**
             * Determines the path to the next image for the widget
             * 
             * @param random
             *            if true, returns a random next image otherwise consecutive
             * @return string representing path to next image to display in widget
             */
            public String getNextImagePath(boolean random) {
                int next;
                if (random) {
                    next = new java.util.Random().nextInt(imagePaths.size());
                    curIndex = next;
                } else {
                    curIndex++;
                    if (curIndex > imagePaths.size()) {
                        curIndex = 0;
                    }
                    next = curIndex;
                }
                return imagePaths.elementAt(next);

            }

            /**
             * Used to retrieve the path to the current image, the most recent return from {@link getNextImagePath}
             * 
             * @return String representing the local path to the image
             */
            public String getCurrentImagePath() {
                String curPath = imagePaths.elementAt(curIndex);
                if (curPath == null) {
                    // no longer available
                    curPath = getNextImagePath(false);
                }
                return curPath;
            }

            /**
             * Downloads feedUrl and parses it for images.
             * 
             * Tested with flickr RSS feeds. Should work with any image enclosure.
             * 
             * @param feedUrl
             *            String representing URL to a supported feed
             */
            private void getAndParseFeedUrl(String feedUrl) {
                try {
                    URL feed = new URL(feedUrl);
                    XmlPullParserFactory parserCreator = XmlPullParserFactory.newInstance();
                    XmlPullParser parser = parserCreator.newPullParser();

                    parser.setInput(feed.openStream(), null);

                    int parserEvent = parser.getEventType();
                    while (parserEvent != XmlPullParser.END_DOCUMENT && !stopRequested) {
                        switch (parserEvent) {
                        case XmlPullParser.START_TAG:
                            String tag = parser.getName();
                            if (tag.compareTo("link") == 0) {
                                String relType = parser.getAttributeValue(null, "rel");
                                if (relType.compareTo("enclosure") == 0) {
                                    String encType = parser.getAttributeValue(null, "type");
                                    if (encType.startsWith("image/")) {
                                        String imageSrc = parser.getAttributeValue(null, "href");
                                        Log.i("Net", "image source = " + imageSrc);

                                        downloadImageToCache(imageSrc);
                                    }
                                }
                            }
                            break;
                        }

                        parserEvent = parser.next();
                    }

                } catch (Exception e) {
                    Log.e(LOG_TAG, "Failed during parsing feed.", e);
                }
            }

            /**
             * Downloads an image, referenced by imageSrc, to the app-local storage
             * 
             * Images paths are kept in {@code imagePaths}
             * 
             * Images could be converted or resized when saved for efficiency and storage minimuzation. Flickr feeds provide small JPEG images, so we'll leave them alone.
             * 
             * @param imageSrc
             *            URL to the actual image to download. Not tested for supported type.
             * 
             */
            private void downloadImageToCache(final String imageSrc) {
                // put this in the background
                new Thread() {

                    public void run() {
                        try {
                            URL image = new URL(imageSrc);
                            String name = String.format("img-%d-%d.png", imageSrc.hashCode(), appWidgetId);

                            File path = new File(getFilesDir(), name);

                            // only download again if we don't have it
                            // NOTE: if the image at the URL changes, this won't
                            // download it again
                            if (!path.exists()) {
                                // get a buffered stream
                                BufferedInputStream is = new BufferedInputStream(image.openStream(), 5000);

                                FileOutputStream fos = openFileOutput(path.getName(), MODE_WORLD_READABLE);

                                while (is.available() > 0) {
                                    byte[] buffer = new byte[5000];
                                    int read = is.read(buffer);
                                    fos.write(buffer, 0, read);
                                }
                                fos.close();
                                is.close();
                            }

                            // keep track of it (Vector is synchronized)
                            if (!imagePaths.contains(path.getCanonicalPath())) {
                                imagePaths.add(path.getCanonicalPath());
                            }
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "Failed to download: " + imageSrc, e);
                        }
                    }
                }.start();

            }
        }

    }

    // state management and utilities
    public static class WidgetState {
        boolean controlsActive;
        boolean paused;
        int updateRateSeconds; // readonly in here
        String feedUrl; // readonly in here

        /**
         * Retrieves a new WidgetState object from the data store (currently SharedPrefs)
         * 
         * @param context
         *            Context to operate under
         * @param appWidgetId
         *            App Widget identifier information to retrieve
         * @return a new WidgetState object
         */
        private static WidgetState getState(Context context, int appWidgetId) {
            WidgetState state = new WidgetState();
            SharedPreferences config = context.getSharedPreferences(ImagesWidgetConfiguration.PREFS_NAME, 0);
            state.updateRateSeconds = config.getInt(String.format(ImagesWidgetConfiguration.PREFS_UPDATE_RATE_FIELD_PATTERN, appWidgetId), -1);
            state.paused = config.getBoolean(String.format(ImagesWidgetConfiguration.PREFS_PAUSED_FIELD_PATTERN, appWidgetId), false);
            state.controlsActive = config.getBoolean(String.format(ImagesWidgetConfiguration.PREFS_CONTROLS_ACTIVE_FIELD_PATTERN, appWidgetId), false);
            state.feedUrl = config.getString(String.format(ImagesWidgetConfiguration.PREFS_FEED_URL_PATTERN, appWidgetId), "");
            return state;
        }

        /**
         * Store the updated state, except updateRateSeconds feedUrl Those are only updated by the configuration activity
         * 
         * @param context
         * @param appWidgetId
         *            App Widget identifier this state represents
         * @param state
         *            The WidgetState to store
         */
        private static void storeState(Context context, int appWidgetId, WidgetState state) {
            SharedPreferences config = context.getSharedPreferences(ImagesWidgetConfiguration.PREFS_NAME, 0);
            SharedPreferences.Editor edit = config.edit();
            edit.putBoolean(String.format(ImagesWidgetConfiguration.PREFS_PAUSED_FIELD_PATTERN, appWidgetId), state.paused);
            edit.putBoolean(String.format(ImagesWidgetConfiguration.PREFS_CONTROLS_ACTIVE_FIELD_PATTERN, appWidgetId), state.controlsActive);
            edit.commit();
        }

        /**
         * Removes a set of state information
         * 
         * @param context
         * @param appWidgetId
         *            App Widget identifier to clear the information for
         */
        private static void deleteStateForId(Context context, int appWidgetId) {
            SharedPreferences config = context.getSharedPreferences(ImagesWidgetConfiguration.PREFS_NAME, 0);
            SharedPreferences.Editor edit = config.edit();

            edit.remove(String.format(ImagesWidgetConfiguration.PREFS_PAUSED_FIELD_PATTERN, appWidgetId));
            edit.remove(String.format(ImagesWidgetConfiguration.PREFS_CONTROLS_ACTIVE_FIELD_PATTERN, appWidgetId));
            edit.remove(String.format(ImagesWidgetConfiguration.PREFS_UPDATE_RATE_FIELD_PATTERN, appWidgetId));
            edit.remove(String.format(ImagesWidgetConfiguration.PREFS_FEED_URL_PATTERN, appWidgetId));

            edit.commit();
        }
    }

}
