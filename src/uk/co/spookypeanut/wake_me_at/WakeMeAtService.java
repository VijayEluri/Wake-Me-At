package uk.co.spookypeanut.wake_me_at;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class WakeMeAtService extends Service implements LocationListener {
    static final String ACTION_FOREGROUND = "uk.co.spookypeanut.wake_me_at.service";
    static final String LOG_NAME = "WakeMeAt";
    private static final int ALARMNOTIFY_ID = 1;

    private static final Class<?>[] mStartForegroundSignature = new Class[] {
        int.class, Notification.class};
    private static final Class<?>[] mStopForegroundSignature = new Class[] {
        boolean.class};
    
    private double mRadius = -1.0;
    private double mDistanceAway = -1.0;
    private Location mFinalDestination = new Location("");
    private String mProvider = "";
    
    private LocationManager locationManager;
    private NotificationManager mNM;
    private Notification mNotification;
    private PendingIntent mIntentOnSelect;
    private Method mStartForeground;
    private Method mStopForeground;
    private Object[] mStartForegroundArgs = new Object[2];
    private Object[] mStopForegroundArgs = new Object[1];
    
    void startForegroundCompat(int id, Notification notification) {
        // If we have the new startForeground API, then use it.
        if (mStartForeground != null) {
            mStartForegroundArgs[0] = Integer.valueOf(id);
            mStartForegroundArgs[1] = notification;
            try {
                mStartForeground.invoke(this, mStartForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w(LOG_NAME, "Unable to invoke startForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w(LOG_NAME, "Unable to invoke startForeground", e);
            }
            return;
        }
        
        // Fall back on the old API.
        setForeground(true);
        mNM.notify(id, notification);
    }
    
    void stopForegroundCompat(int id) {
        // If we have the new stopForeground API, then use it.
        if (mStopForeground != null) {
            mStopForegroundArgs[0] = Boolean.TRUE;
            try {
                mStopForeground.invoke(this, mStopForegroundArgs);
            } catch (InvocationTargetException e) {
                // Should not happen.
                Log.w(LOG_NAME, "Unable to invoke stopForeground", e);
            } catch (IllegalAccessException e) {
                // Should not happen.
                Log.w(LOG_NAME, "Unable to invoke stopForeground", e);
            }
            return;
        }
        
        // Fall back on the old API.  Note to cancel BEFORE changing the
        // foreground state, since we could be killed at that point.
        mNM.cancel(id);
        setForeground(false);
    }
    
    @Override
    public void onCreate() {
        mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);


        try {
            mStartForeground = getClass().getMethod("startForeground",
                    mStartForegroundSignature);
            mStopForeground = getClass().getMethod("stopForeground",
                    mStopForegroundSignature);
        } catch (NoSuchMethodException e) {
            // Running on an older platform.
            mStartForeground = mStopForeground = null;
        }

    }

    @Override
    public void onStart(Intent intent, int startId) {
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOG_NAME, "onStartCommand()");
        Bundle extras = intent.getExtras();

        mFinalDestination.setLatitude(extras.getDouble("latitude"));
        mFinalDestination.setLongitude(extras.getDouble("longitude"));
        mRadius = extras.getFloat("radius");
        mProvider = extras.getString("provider");
        Log.d(LOG_NAME, "Provider: \"" + mProvider + "\"");
        Log.d(LOG_NAME,
            "Passed latlong: " + mFinalDestination.getLatitude() +
            ", " + mFinalDestination.getLongitude());
        
        locationManager =
            (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        registerLocationListener();
        
        handleCommand(intent);
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        Toast.makeText(getApplicationContext(), R.string.foreground_service_started,
                Toast.LENGTH_SHORT).show();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // Make sure our notification is gone.
        stopForegroundCompat(R.string.foreground_service_started);
        unregisterLocationListener();
    }

    public boolean stopService(Intent name) {
        Log.d(LOG_NAME, "TrackRecordingService.stopService");
        unregisterLocationListener();
        return super.stopService(name);
      }

    public void registerLocationListener() {
        Log.d(LOG_NAME, "registerLocationListener()");
        if (locationManager == null) {
          Log.e(LOG_NAME,
              "TrackRecordingService: Do not have any location manager.");
          return;
        }
        Log.d(LOG_NAME,
            "Preparing to register location listener w/ TrackRecordingService...");
        try {
          long desiredInterval = 10;
          locationManager.requestLocationUpdates(
              mProvider, desiredInterval,
              10, WakeMeAtService.this);
        } catch (RuntimeException e) {
          Log.e(LOG_NAME,
              "Could not register location listener: " + e.getMessage(), e);
        }
      }

    public void unregisterLocationListener() {
        if (locationManager == null) {
          Log.e(LOG_NAME,
              "TrackRecordingService: Do not have any location manager.");
          return;
        }
        locationManager.removeUpdates(this);
        Log.d(LOG_NAME,
            "Location listener now unregistered w/ TrackRecordingService.");
      }
 
    void handleCommand(Intent intent) {
        if (ACTION_FOREGROUND.equals(intent.getAction())) {
            // In this sample, we'll use the same text for the ticker and the expanded notification
            CharSequence text = getText(R.string.foreground_service_started);

            // Set the icon, scrolling text and timestamp
            mNotification = new Notification(R.drawable.x, text,
                    System.currentTimeMillis());

            // The PendingIntent to launch our activity if the user selects this notification
            mIntentOnSelect = PendingIntent.getActivity(this, 0,
                    new Intent(this, WakeMeAt.class), 0);

            // Set the info for the views that show in the notification panel.
            mNotification.setLatestEventInfo(this, getText(R.string.foreground_service_started),
                           text, mIntentOnSelect);
            
            startForegroundCompat(R.string.foreground_service_started, mNotification);
            
        }
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(Location location) {
        mDistanceAway = location.distanceTo(mFinalDestination);
        String message = getText(R.string.notif_pre).toString() +
                roundToDecimals(mDistanceAway, 2) +
                getText(R.string.notif_post).toString();
        mNotification.setLatestEventInfo(this, getText(R.string.app_name), message, mIntentOnSelect);
        mNM.notify(R.string.foreground_service_started, mNotification);
        if (mDistanceAway < mRadius) {
            soundAlarm();
        }
        
    }

    public void soundAlarm() {
        String debugMessage = "Alarm sounded";
        Toast.makeText(getApplicationContext(), debugMessage,
                Toast.LENGTH_SHORT).show();
        int icon = R.drawable.x;
        CharSequence tickerText = "Approaching destination";
        long when = System.currentTimeMillis();
        Context context = getApplicationContext();
        CharSequence contentTitle = "Approaching destination";
        CharSequence contentText = "Approaching destination";
        Notification notification = new Notification(icon, tickerText, when);
        Intent notificationIntent = new Intent(this, WakeMeAt.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        notification.defaults |= Notification.DEFAULT_SOUND;
        notification.defaults |= Notification.DEFAULT_VIBRATE;
        
        notification.setLatestEventInfo(context, contentTitle, contentText, contentIntent);
        mNM.notify(ALARMNOTIFY_ID, notification);

    }
    public static double roundToDecimals(double d, int c) {
        int temp=(int)((d*Math.pow(10,c)));
        return (((double)temp)/Math.pow(10,c));
        }
    
    @Override
    public void onProviderDisabled(String provider) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onProviderEnabled(String provider) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // TODO Auto-generated method stub
        
    }
    

}
