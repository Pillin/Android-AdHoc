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


public class AdHocApp extends android.app.Application {
    final static String TAG = "AdHocApp";
    public static String app_name;
    
    final static int ERROR_ROOT = 1;
    final static int ERROR_OTHER = 2;
    final static int ERROR_SUPPLICANT = 3;
    final static int ERROR_ASSETS = 4;

    final static int NOTIFY_RUNNING = 0;
    final static int NOTIFY_ERROR = 1;
    
    SharedPreferences prefs;
    public AdHocService adHocService = null;
    private AdHocActivity adHocActivity = null;
    private WifiManager wifiManager;
    private boolean previousWifiState;
    
    private Toast toast;
    private NotificationManager notificationManager;
    private Notification notification;
    private Notification notificationError;
    

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, String.format(getString(R.string.creating), this.getClass().getSimpleName()));
        NativeHelper.setup(this, getString(R.string.missedAssetsFiles));
        
        app_name = getString(R.string.app_name);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        toast = Toast.makeText(this, "", Toast.LENGTH_SHORT);
        
        notification = new Notification(R.drawable.barnacle, getString(R.string.notify_running), 0);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        
        String notify_error = getString(R.string.notify_error);
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, AdHocActivity.class), 0);
        notificationError = new Notification(R.drawable.barnacle_error,notify_error, 0);
        notificationError.setLatestEventInfo(this, app_name, notify_error, pi);
        notificationError.flags = Notification.FLAG_AUTO_CANCEL;

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        if (previousWifiState = wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(false);
        }
        Log.d(TAG, String.format(getString(R.string.created), this.getClass().getSimpleName()));
        
        if (!NativeHelper.unzipAssets(this)) {
            Log.e(TAG, getString(R.string.unpackerr));
           this.failed(ERROR_ASSETS);
        }
    }

    @Override
    public void onTerminate() {
        if (adHocService != null) {
        	Log.e(TAG, getString(R.string.stopAppRunningService));
            adHocService.stopRequest();
        }
        super.onTerminate();
    }
    

    public void startService() {
    	notificationManager.cancel(NOTIFY_ERROR);
    	this.pickUpNewIP();
    	if (adHocService == null) {
            startService(new Intent(this, AdHocService.class));
        }
    }
    

    private void pickUpNewIP() {
    	SharedPreferences.Editor e = prefs.edit();
    	String ipFormat = getString(R.string.ipFormat);
    	ipFormat = String.format(ipFormat, (int)(Math.random()*255), (int)(Math.random()*255));
    	e.putString(getString(R.string.lan_gw), ipFormat);
    	e.commit();
    	Log.i(TAG, "Generated IP: " + ipFormat);
	}

	public void stopService() {
		if (adHocService != null) {
            adHocService.stopRequest();
        }
    }
    

    public int getState() {
    	int state = AdHocService.STATE_STOPPED;
        if (adHocService != null) {
        	state = adHocService.getState();
        }
        return state;
    }
    

    public boolean isRunning() {
        return getState() == AdHocService.STATE_RUNNING;
    }
    
    
    void setStatusActivity(AdHocActivity sa) {
        adHocActivity = sa;
    }
    
    
    void serviceStarted(AdHocService service) {
        this.adHocService = service;
        service.startRequest();
    }
    

    void updateStatus() {
        if (adHocActivity != null) {
            adHocActivity.updateActivityContent();
        }
    }
    

    void updateToast(String msg, boolean islong) {
        toast.setText(msg);
        toast.setDuration(islong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.show();
    }
    

    void processStarted() {
    	Intent ni = new Intent(this, AdHocActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni, 0);
        String notify_running = getString(R.string.notify_running); 
        notification.setLatestEventInfo(this, app_name, notify_running, pi);
        notificationManager.notify(NOTIFY_RUNNING, notification);
        adHocService.startForegroundCompat(NOTIFY_RUNNING, notification);
        Log.d(TAG, getString(R.string.adhocStarted));
    }
    

    void processStopped() {
    	notificationManager.cancel(NOTIFY_RUNNING);
        if (adHocService != null) {
        	adHocService.stopSelf();
        }
        adHocService = null;
        updateStatus();
        Log.d(TAG, getString(R.string.adhocStoped));
        if (previousWifiState) {
            wifiManager.setWifiEnabled(true);
        }
    }
    

    void failed(int err) {
        if (adHocActivity != null) {
            if (err == ERROR_ROOT) {
            	// TODO: FVALVERD This method is deprecated. Use the new DialogFragment class with FragmentManager instead
                adHocActivity.showDialog(AdHocActivity.DLG_ROOT);
            } else if (err == ERROR_SUPPLICANT) {
            	// TODO: FVALVERD This method is deprecated. Use the new DialogFragment class with FragmentManager instead
                adHocActivity.showDialog(AdHocActivity.DLG_SUPPLICANT);
            } else if (err == ERROR_OTHER) {
            	// TODO: FVALVERD This method is deprecated. Use the new DialogFragment class with FragmentManager instead
                adHocActivity.showDialog(AdHocActivity.DLG_ERROR);
            } else if (err == ERROR_ASSETS) {
            	// TODO: FVALVERD This method is deprecated. Use the new DialogFragment class with FragmentManager instead
            	adHocActivity.showDialog(AdHocActivity.DLG_ASSETS);
            }
        }
        if ((adHocActivity == null) || !adHocActivity.hasWindowFocus()) {
            Log.d(TAG, getString(R.string.notifyingError));
            notificationManager.notify(NOTIFY_ERROR, notificationError);
        }
    }
    

    void cleanUpNotifications() {
        if ((adHocService != null) && (adHocService.getState() == AdHocService.STATE_STOPPED)) {
            processStopped(); // clean up notifications
        }
    }
}
