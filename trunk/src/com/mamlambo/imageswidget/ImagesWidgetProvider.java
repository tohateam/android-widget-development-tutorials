package com.mamlambo.imageswidget;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import android.widget.RemoteViews;

public class ImagesWidgetProvider extends AppWidgetProvider {
    private static final String LOG_TAG = "ImagesWidgetProvider";

    public static final String URI_SCHEME = "images_widget";
    private static final int[] IMAGES = { R.drawable.null01, R.drawable.null02, R.drawable.null03, R.drawable.null04, R.drawable.null05, R.drawable.null06, R.drawable.null07,
            R.drawable.null08, R.drawable.null09 };

    @Override
    public void onEnabled(Context context) {
        // This is only called once, regardless of the number of widgets of this
        // type
        // We do not have any global initialization
        Log.i(LOG_TAG, "onEnabled()");
        super.onEnabled(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Log.d(LOG_TAG, "onUpdate(): ");

        for (int appWidgetId : appWidgetIds) {

            int imageNum = (new java.util.Random().nextInt(IMAGES.length));

            RemoteViews remoteView = new RemoteViews(context.getPackageName(), R.layout.widget);

            remoteView.setImageViewResource(R.id.image, IMAGES[imageNum]);

            appWidgetManager.updateAppWidget(appWidgetId, remoteView);

        }
        // super.onUpdate(context, appWidgetManager, appWidgetIds);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        Log.d(LOG_TAG, "onDelete()");

        for (int appWidgetId : appWidgetIds) {

            // stop alarm
            Intent widgetUpdate = new Intent();
            widgetUpdate.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
            widgetUpdate.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            widgetUpdate.setData(Uri.withAppendedPath(Uri.parse(URI_SCHEME + "://widget/id/"), String.valueOf(appWidgetId)));
            PendingIntent newPending = PendingIntent.getBroadcast(context, 0, widgetUpdate, PendingIntent.FLAG_UPDATE_CURRENT);

            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            alarms.cancel(newPending);

            // remove preference
            Log.d(LOG_TAG, "Removing preference for id " + appWidgetId);
            SharedPreferences config = context.getSharedPreferences(ImagesWidgetConfiguration.PREFS_NAME, 0);
            SharedPreferences.Editor configEditor = config.edit();

            configEditor.remove(String.format(ImagesWidgetConfiguration.PREFS_UPDATE_RATE_FIELD_PATTERN, appWidgetId));
            configEditor.commit();
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
        } else if (AppWidgetManager.ACTION_APPWIDGET_UPDATE.equals(action)) {

            if (!URI_SCHEME.equals(intent.getScheme())) {
                // if the scheme doesn't match, that means it wasn't from the
                // alarm
                // either it's the first time in (even before the configuration
                // is done) or after a reboot or update

                final int[] appWidgetIds = intent.getExtras().getIntArray(AppWidgetManager.EXTRA_APPWIDGET_IDS);

                for (int appWidgetId : appWidgetIds) {

                    // get the user settings for how long to schedule the update
                    // time for
                    SharedPreferences config = context.getSharedPreferences(ImagesWidgetConfiguration.PREFS_NAME, 0);
                    int updateRateSeconds = config.getInt(String.format(ImagesWidgetConfiguration.PREFS_UPDATE_RATE_FIELD_PATTERN, appWidgetId), -1);
                    if (updateRateSeconds != -1) {
                        Log.i(LOG_TAG, "Starting recurring alarm for id " + appWidgetId);
                        Intent widgetUpdate = new Intent();
                        widgetUpdate.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                        widgetUpdate.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { appWidgetId });

                        // make this pending intent unique by adding a scheme to
                        // it
                        widgetUpdate.setData(Uri.withAppendedPath(Uri.parse(ImagesWidgetProvider.URI_SCHEME + "://widget/id/"), String.valueOf(appWidgetId)));
                        PendingIntent newPending = PendingIntent.getBroadcast(context, 0, widgetUpdate, PendingIntent.FLAG_UPDATE_CURRENT);

                        // schedule the updating
                        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
                        alarms.setRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), updateRateSeconds * 1000, newPending);
                    }
                }
            }
            super.onReceive(context, intent);
        } else {
            super.onReceive(context, intent);
        }
    }

}
