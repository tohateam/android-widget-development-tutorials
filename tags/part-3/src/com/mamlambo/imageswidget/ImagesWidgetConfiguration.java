package com.mamlambo.imageswidget;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

public class ImagesWidgetConfiguration extends Activity {
    public static final String PREFS_NAME = "ImagesWidgetPrefs";
    public static final String PREFS_UPDATE_RATE_FIELD_PATTERN = "UpdateRate-%d";
    public static final String PREFS_CONTROLS_ACTIVE_FIELD_PATTERN = "ControlsActive-%d";
    public static final String PREFS_PAUSED_FIELD_PATTERN = "Paused-%d";
    public static final String PREFS_FEED_URL_PATTERN = "FeedURL-%d";
    
    // change image every minute, by default
    // TODO: for a real widget, probably want to switch the units to minutes
    private static final int PREFS_UPDATE_RATE_DEFAULT = 60;
    private static final String PREFS_FEED_URL_DEFAULT = "http://api.flickr.com/services/feeds/photos_public.gne?id=26648248@N04&lang=en-us&format=atom";

    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // get any data we were launched with
        Intent launchIntent = getIntent();
        Bundle extras = launchIntent.getExtras();
        if (extras != null) {
            appWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);

            Intent cancelResultValue = new Intent();
            cancelResultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
            setResult(RESULT_CANCELED, cancelResultValue);
        } else {
            // only launch if it's for configuration
            // Note: when you launch for debugging, this does prevent this
            // activity from running. We could also turn off the intent
            // filtering for main activity.
            // But, to debug this activity, we can also just comment the
            // following line out.
            finish();
        }

        setContentView(R.layout.configuration);

        final SharedPreferences config = getSharedPreferences(PREFS_NAME, 0);
        final EditText updateRateEntry = (EditText) findViewById(R.id.update_rate_entry);
        final EditText feedUrlEntry = (EditText)findViewById(R.id.feed_url_entry);

        updateRateEntry.setText(String.valueOf(config.getInt(String.format(PREFS_UPDATE_RATE_FIELD_PATTERN, appWidgetId), PREFS_UPDATE_RATE_DEFAULT)));
        feedUrlEntry.setText(config.getString(String.format(PREFS_FEED_URL_PATTERN,  appWidgetId), PREFS_FEED_URL_DEFAULT));

        Button saveButton = (Button) findViewById(R.id.save_button);

        saveButton.setOnClickListener(new OnClickListener() {

            public void onClick(View v) {
                int updateRateSeconds = Integer.parseInt(updateRateEntry.getText().toString());
                String feedUrl = feedUrlEntry.getText().toString();

                // store off the user setting for update timing
                SharedPreferences.Editor configEditor = config.edit();

                configEditor.putInt(String.format(PREFS_UPDATE_RATE_FIELD_PATTERN, appWidgetId), updateRateSeconds);
                configEditor.putString(String.format(PREFS_FEED_URL_PATTERN, appWidgetId), feedUrl);
                
                configEditor.commit();

                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {

                    // tell the app widget manager that we're now configured
                    Intent resultValue = new Intent();
                    resultValue.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                    setResult(RESULT_OK, resultValue);

                    Intent widgetUpdate = new Intent();
                    widgetUpdate.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
                    widgetUpdate.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] { appWidgetId });

                    // make this pending intent unique
                    widgetUpdate.setData(Uri.withAppendedPath(Uri.parse(ImagesWidgetProvider.URI_SCHEME + "://widget/id/"), String.valueOf(appWidgetId)));
                    PendingIntent newPending = PendingIntent.getBroadcast(getApplicationContext(), 0, widgetUpdate, PendingIntent.FLAG_UPDATE_CURRENT);

                    // schedule the new widget for updating
                    AlarmManager alarms = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
                    alarms.setRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime(), updateRateSeconds * 1000, newPending);
                }

                // activity is now done
                finish();
            }
        });
    }
}