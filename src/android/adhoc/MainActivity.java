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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ToggleButton;


public class MainActivity extends Activity {
	
	final static int DLG_ROOT = 1;
    final static int DLG_ERROR = 2;
    final static int DLG_SUPPLICANT = 3;
	
	private BarnacleApp app;
    private ToggleButton onoff;
    

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        app = (BarnacleApp)getApplication();
        setContentView(R.layout.main);

        onoff = (ToggleButton) findViewById(R.id.onoff);
        onoff.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onoff.setPressed(true);
                if (onoff.isChecked()) {
                	app.startService();
                }
                else {
                    app.stopService();
                }
            }
        });

        app.setStatusActivity(this);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        app.setStatusActivity(null);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.menu_prefs:
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return(super.onOptionsItemSelected(item));
    }
    
    @Override
    protected Dialog onCreateDialog(int id) {
        // TODO: these should not create and remove dialogs, but restore and dismiss
        if (id == DLG_ROOT) {
            return (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("Root Access")
                .setMessage("Barnacle requires 'su' to access the hardware! Please, make sure you have root access.")
                .setPositiveButton("Help", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Uri uri = Uri.parse(getString(R.string.rootUrl));
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    }
                 })
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    	removeDialog(DLG_ROOT);
                    }
                 })
                .create();
        }
        if (id == DLG_SUPPLICANT) {
            return (new AlertDialog.Builder(this))
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle("Supplicant not available")
                .setMessage("Barnacle had trouble starting wpa_supplicant. Try again but set 'Skip wpa_supplicant' in settings.")
                .setPositiveButton("Do it now!", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        app.prefs.edit().putBoolean(getString(R.string.lan_wext), true).commit();
                        app.updateToast("Settings updated, try again...", true);
                    }
                })
                .setNegativeButton("Close", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                    	removeDialog(DLG_ROOT);
                    }
                })
                .create();
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        update();
        app.cleanUpNotifications();
    }

    /*
     * Update Toggle Button Start-Stop
     */
    void update() {
        int state = app.getState();

        if (state == BarnacleService.STATE_STOPPED) {
            onoff.setChecked(false);
            return; // not ready yet! keep the old log
        }

        BarnacleService svc = app.service;
        if (svc == null) return; // unexpected race condition

        if (state == BarnacleService.STATE_STARTING) {
            onoff.setPressed(true);
            onoff.setChecked(true);
            return;
        }

        if (state != BarnacleService.STATE_RUNNING) {
        	return;
        }

        // STATE_RUNNING
        onoff.setPressed(false);
        onoff.setChecked(true);
    }
}
