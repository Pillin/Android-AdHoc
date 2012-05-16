/*
*  This file is part of Barnacle Wifi Tether
*  Copyright (C) 2010 by Szymon Jakubczak
*
*  This program is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation, either version 3 of the License, or
*  (at your option) any later version.
*
*  This program is distributed in the hope that it will be useful,
*  but WITHOUT ANY WARRANTY; without even the implied warranty of
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*  GNU General Public License for more details.
*
*  You should have received a copy of the GNU General Public License
*  along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package android.adhoc;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;


/**
* Manages preferences, activities and prepares the service
*/
public class BarnacleApp extends android.app.Application {
    final static String TAG = "BarnacleApp";
    
    final static String ACTION_TOGGLE = "android.hlmp.bernacle.TOGGLE_STATE";
    final static String ACTION_CHECK = "android.hlmp.bernacle.CHECK_STATE";
    final static String ACTION_CHANGED = "android.hlmp.bernacle.STATE_CHANGED";

    final static int ERROR_ROOT = 1;
    final static int ERROR_OTHER = 2;
    final static int ERROR_SUPPLICANT = 3;

    SharedPreferences prefs;
    private MainActivity statusActivity = null;
    private Toast toast;

    private WifiManager wifiManager;

    private NotificationManager notificationManager;
    private Notification notification;
    
    private Notification notificationError;
    final static int NOTIFY_RUNNING = 0;
    final static int NOTIFY_ERROR = 1;

    public BarnacleService service = null;
    public static String app_name;

    private boolean shouldDisableWifi;
  

    @Override
    public void onCreate() {
        super.onCreate();
        NativeHelper.setup(this);
        app_name = getString(R.string.app_name);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // TODO: FVALVERD esto debería ser un parametro del usuario y no determinarlo en la aplicación
        if (prefs.getString(getString(R.string.lan_gw), "").equals("")) {
        	SharedPreferences.Editor e = prefs.edit();
        	// TODO: FVALVERD parametrizar el 170.160.X.X
        	String myIP = "170.160." + String.valueOf((int)(Math.random() * 255)) + "." + String.valueOf((int)(Math.random() * 255));
        	e.putString(getString(R.string.lan_gw), myIP);
        	e.commit();
        	Log.i(TAG, "Generated IP: " + myIP);
        }
        
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        toast = Toast.makeText(this, "", Toast.LENGTH_SHORT);
        
        notification = new Notification(R.drawable.barnacle, getString(R.string.notify_running), 0);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        
        String notify_error = getString(R.string.notify_error);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        notificationError = new Notification(R.drawable.barnacle_error,notify_error, 0);
        notificationError.setLatestEventInfo(this, app_name, notify_error, pi);
        notificationError.flags = Notification.FLAG_AUTO_CANCEL;

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
            shouldDisableWifi = true;
        }
        wifiManager.startScan();
        Log.d(TAG, this.getClass().getSimpleName() + " Created!");
    }

    @Override
    public void onTerminate() {
        if (service != null) {
            Log.e(TAG, "The app is terminated while the service is running!");
            service.stopRequest();
        }
        if (shouldDisableWifi) {
            wifiManager.setWifiEnabled(false);
        }
        super.onTerminate();
    }

    public void startService() {
        if (service == null) {
            startService(new Intent(this, BarnacleService.class));
        }
    }

    public void stopService() {
        if (service != null) {
            service.stopRequest();
        }
    }

    public int getState() {
    	int state = BarnacleService.STATE_STOPPED;
        if (service != null) {
        	state = service.getState();
        }
        return state;
    }

    public boolean isChanging() {
        return getState() == BarnacleService.STATE_STARTING;
    }

    public boolean isRunning() {
        return getState() == BarnacleService.STATE_RUNNING;
    }

    public boolean isStopped() {
        return getState() == BarnacleService.STATE_STOPPED;
    }

    void setStatusActivity(MainActivity sa) {
        statusActivity = sa;
    }
    
    void serviceStarted(BarnacleService service) {
        this.service = service;
        service.startRequest();
    }

    void updateStatus() {
        if (statusActivity != null) {
            statusActivity.update();
        }
    }

    void updateToast(String msg, boolean islong) {
        toast.setText(msg);
        toast.setDuration(islong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.show();
    }

    void processStarted() {
        Log.d(TAG, "Wireless ADHOC Started!");
        Intent ni = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni, 0);
        String notify_running = getString(R.string.notify_running); 
        notification.setLatestEventInfo(this, app_name, notify_running, pi);
        notificationManager.notify(NOTIFY_RUNNING, notification);
        service.startForegroundCompat(NOTIFY_RUNNING, notification);
    }

    void processStopped() {
        Log.d(TAG, "Wireless ADHOC Stoped!");
        notificationManager.cancel(NOTIFY_RUNNING);
        if (service != null) {
        	service.stopSelf();
        }
        service = null;
        updateStatus();
    }

    void failed(int err) {
        if (statusActivity != null) {
            if (err == ERROR_ROOT) {
                statusActivity.showDialog(MainActivity.DLG_ROOT);
            } else if (err == ERROR_SUPPLICANT) {
                statusActivity.showDialog(MainActivity.DLG_SUPPLICANT);
            } else if (err == ERROR_OTHER) {
                statusActivity.showDialog(MainActivity.DLG_ERROR);
            }
        }
        if ((statusActivity == null) || !statusActivity.hasWindowFocus()) {
//        	TODO: FVALVERDE pasar el texto a string.xml
            Log.d(TAG, "Notifying error");
            notificationManager.notify(NOTIFY_ERROR, notificationError);
        }
    }

    void cleanUpNotifications() {
        if ((service != null) && (service.getState() == BarnacleService.STATE_STOPPED)) {
            processStopped(); // clean up notifications
        }
    }
}

