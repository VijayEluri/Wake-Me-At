package uk.co.spookypeanut.wake_me_at;
/*
    This file is part of Wake Me At. Wake Me At is the legal property
    of its developer, Henry Bush (spookypeanut).

    Wake Me At is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Wake Me At is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Wake Me At, in the file "COPYING".  If not, see
    <http://www.gnu.org/licenses/>.
 */
import java.io.IOException;
import java.util.List;
import java.util.Locale;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.GestureDetector;
import android.view.GestureDetector.OnDoubleTapListener;
import android.view.GestureDetector.OnGestureListener;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapActivity;
import com.google.android.maps.MapController;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;
import com.google.android.maps.Projection;

public class GetLocationMap extends MapActivity
implements LocationListener {
    public static final String PREFS_NAME = "WakeMeAtPrefs";
    private String LOG_NAME;
    Context mContext;
    LayoutInflater mInflater;
    MapView mapView;
    UnitConverter uc;
    Geocoder mGeocoder;
    static SearchManager mSearchManager;
    // Need handler for callbacks to the UI thread
    final Handler mHandler = new Handler();

    Location mCurrLoc;
    double mOrigLat, mOrigLong;
    GeoPoint mDest;
    String mNick;
    double mRadius;
    
    private List<Address> mResults;
    List<Address> mDestAddresses = null;
    Dialog mResultsDialog;
    boolean mSearching;
    String mSearchTerm;
    // If our search fails through lack of data, retry this many times
    int mTries = 0;
    private static final int MAX_TRIES = 10;

    ProgressDialog mProgressDialog;
    boolean mSatellite = false;

    /* This runnable is called when the address of the potential
     * destination has been retrieved, to cancel the progress dialog */
    final Runnable mGotAddresses = new Runnable() {
        public void run() {
            mProgressDialog.cancel();
            gotDestinationAddress();
        }
    };

    /* This runnable is called when the search results have been
     * retrieved, to cancel the progress dialog */
    final Runnable mGotSearchResults = new Runnable() {
        public void run() {
            mProgressDialog.cancel();
            resultsDialog();
        }
    };

    @Override
    public void onCreate(Bundle icicle) {
        LOG_NAME = (String) getText(R.string.app_name_nospaces);
        Log.d(LOG_NAME, "GetLocationMap.onCreate()");
        mContext = this;
        super.onCreate(icicle);
        // REF#0023: Setting a content view for a mapview is kinda slow (3 sec
        // or so on my Nexus One. However, we don't seem to be able to pop up a
        // progress window, as both need access to the ui. This sucks.
        setContentView(R.layout.get_location_map);

        setVolumeControlStream(AudioManager.STREAM_ALARM);
        mSearchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
        mInflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        mGeocoder = new Geocoder(this, Locale.getDefault());
        // We need this just to print out distances in the correct format, etc
        uc = new UnitConverter(this, "m");

        mapView = (MapView) findViewById(R.id.mapview);
        mapView.setBuiltInZoomControls(true);
        setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);
        // This it probably pointless here: mSatellite should always be false
        mapView.setSatellite(mSatellite);
        
        // Get the information from the intent 
        Bundle extras = this.getIntent().getExtras();
        // We need the location to place our starting pointer
        mOrigLat = extras.getDouble("latitude");
        mOrigLong = extras.getDouble("longitude");
        // We need the radius to draw it on the map
        mRadius = extras.getDouble("radiusMetres");
        // We need the nick to pre-populate the search box
        mSearchTerm = extras.getString("nick");
        
        // If the location is the (invalid) default, we start from our current location
        if (mOrigLat == 1000 && mOrigLong == 1000) {
            Location currLoc = getCurrentLocation();
            if (currLoc != null) {
                mOrigLat = currLoc.getLatitude();
                mOrigLong = currLoc.getLongitude();
            }
        }
        moveDestinationTo(mOrigLat, mOrigLong);

        // See onRetainNonConfigurationInstance for the information that
        // we receive here
        final Bundle data = (Bundle) getLastNonConfigurationInstance();

        if (data != null) {
            // If we have an existing instance, we get the location,
            // the last search term, and whether the search box is open
            mSearchTerm = data.getString("mSearchTerm");
            mSearching = data.getBoolean("mSearching");
            double lat = (double) data.getInt("mDestLat") / 1E6;
            double longi = (double) data.getInt("mDestLong") / 1E6;
            moveMapTo(lat, longi);
        } else {
            // If we don't have an existing instance, we move the map to the
            // starting location and open the search box
            moveMapTo(mOrigLat, mOrigLong);
            mSearching = true;
        }
        if (mSearching) {
            onSearchRequested();
        }
    }

    /** (non-Javadoc)
     * @see android.app.Activity#onRetainNonConfigurationInstance()
     * Generally, when the screen is rotated, the activity gets started again
     * from scratch. In this case, that would be *really* annoying, if we had
     * chosen a location, got search up, etc. So this method gives us the 
     * ability to pass some information to future existences of ourself, and
     * handle it in onCreate
     */
    @Override
    public Object onRetainNonConfigurationInstance() {
        Bundle returnBundle = new Bundle();
        returnBundle.putString("mSearchTerm", mSearchTerm);
        returnBundle.putBoolean("mSearching", mSearching);
        mDest = mapView.getProjection().fromPixels(
                mapView.getWidth()/2,
                mapView.getHeight()/2);
        returnBundle.putInt("mDestLat",mDest.getLatitudeE6());
        returnBundle.putInt("mDestLong", mDest.getLongitudeE6());
        return returnBundle;
    }

    /* (non-Javadoc)
     * @see com.google.android.maps.MapActivity#onNewIntent(android.content.Intent)
     */
    @Override
    public void onNewIntent(Intent intent) {
        handleIntent(intent);
    }

    /**
     * When passed a new intent, run this.
     * Can be either via onNewIntent or onCreate
     * TODO: Seems to be run only from onNewIntent atm: maybe it could be
     * removed? But I'll leave it for now
     * @param intent
     */
    private void handleIntent(Intent intent) {
        setIntent(intent);
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            // We've finished searching then
            mSearching = false;
            String query = intent.getStringExtra(SearchManager.QUERY);
            doSearch(query);
        }
    }

    /**
     * Toggle the map mode between map and satellite
     */
    private void toggleMapMode() {
        mSatellite = !mSatellite;
        mapView.setSatellite(mSatellite);
    }

    /**
     * Move the destination marker on the map
     * @param latitude The new latitude of the marker
     * @param longitude The new longitude of the marker
     */
    private void moveDestinationTo(double latitude, double longitude) {
        List<Overlay> mapOverlays = mapView.getOverlays();

        DestOverlay destOverlay = new DestOverlay(mContext, latitude, longitude, mRadius);
        mapOverlays.clear();
        mapOverlays.add(destOverlay);
    }

    /**
     * Move the map to a given point
     * @param latitude The new latitude
     * @param longitude The new longitude
     */
    private void moveMapTo(double latitude, double longitude) {
        GeoPoint location = new GeoPoint((int) (latitude * 1E6),
                                            (int) (longitude * 1E6));
        moveMapTo(location);
    }

    /**
     * Move the map to a given point
     * @param location The new location
     */
    private void moveMapTo(GeoPoint location) {
        if (location != null) {
            MapController mc = mapView.getController();
            // We also set the zoom level. Should we?
            mc.setZoom(15);
            mc.animateTo(location);
        } else {
            Log.e(LOG_NAME, "Location to move to was null");
        }
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.mn_get_location_map, menu);
        return true;
    }

    /* (non-Javadoc)
     * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
        case R.id.mn_orig_loc:
            // Move the map back to the original location
            moveMapTo(mOrigLat, mOrigLong);
            return true;
        case R.id.mn_search:
            // Pop-up the search dialog
            onSearchRequested();
            return true;
        case R.id.mn_curr_loc:
            // Move the centre of the map to the current location
            Location here = getCurrentLocation();
            if (here != null) {
                moveMapTo(here.getLatitude(), here.getLongitude());
            } else {
                Log.e(LOG_NAME, "Location inaccessible");
            }
            return true;
        case R.id.mn_satellite:
            toggleMapMode();
            // Switch the text of the menu item depending which mode we're
            // currently in
            if (mSatellite) {
                item.setTitle(R.string.mn_map);
            } else {
                item.setTitle(R.string.mn_satellite);
            }
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    
    /**
     * getCurrentLocation should never return null. Let's pop up a long
     * toast, explaining what has happened, and then return a location in
     * the middle of the Atlantic
     * @return A generic Location 
     */
    private Location cantGetLocation() {
        // The weird thing is that this is what my device (Nexus One) does
        // anyway by default, but I've had report of a force close
        Toast.makeText(mContext, R.string.cant_get_location_msg,
                       Toast.LENGTH_LONG).show();
        Location fakeLocation = new Location("");
        fakeLocation.setLatitude(0.0);
        fakeLocation.setLongitude(0.0);
        return fakeLocation;
    }
    
    /**
     * Get the current location via GPS
     * @return Current location
     */
    private Location getCurrentLocation() {
        Location currentLocation = new Location("");
        LocationManager locMan;
        locMan = (LocationManager) getSystemService(LOCATION_SERVICE);
        locMan.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                1000, 2, this);
        String provider = locMan.getBestProvider(new Criteria(), true);
        if (provider == null) {
            // If there is no best location provider, something has gone
            // seriously wrong. But there's not much we can do.
            Log.wtf(LOG_NAME, "Provider is null");
            return cantGetLocation();
        }
        if(!locMan.isProviderEnabled(provider)){
            Toast.makeText(mContext, R.string.providerDisabledMessage,
                           Toast.LENGTH_LONG).show();
            Log.wtf(LOG_NAME, "Provider is disabled");
            return cantGetLocation();
        }
        currentLocation = locMan.getLastKnownLocation(provider);
        locMan.removeUpdates(this);

        if(currentLocation == null){
            // Again: if this happens, I don't know what went wrong, nor
            // what we can do about it.
            Log.wtf(LOG_NAME, "Return value from getLastKnownLocation is null");
            return cantGetLocation();
        }
        return currentLocation;
    }

    /* (non-Javadoc)
     * @see com.google.android.maps.MapActivity#isRouteDisplayed()
     * This is required to inherit, but there is never a route displayed
     */
    @Override
    protected boolean isRouteDisplayed() {
        return false;
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onLocationChanged(android.location.Location)
     * We're not interested in the location changing, so just return
     */
    @Override
    public void onLocationChanged(Location location) {
        return;
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onProviderDisabled(java.lang.String)
     * If the provider is disabled, do nothing. We'll survive.
     */
    @Override
    public void onProviderDisabled(String provider) {
        return;
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onProviderEnabled(java.lang.String)
     * If the location provider gets enabled, do nothing
     */
    @Override
    public void onProviderEnabled(String provider) {
        return;
    }

    /* (non-Javadoc)
     * @see android.location.LocationListener#onStatusChanged(java.lang.String, int, android.os.Bundle)
     * We should probably think about handling this correctly, but the location
     * on the map is not really that crucial so for now, we just ignore it.
     */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        return;
    }

    /**
     * Display a pop-up message explaining to the user how they should go about
     * choosing a location
     */
    private void howToSelectLocationInfo() {
        Toast.makeText(getApplicationContext(),
                       R.string.how_to_select_location,
                       Toast.LENGTH_LONG).show();
    }

    @Override
    public boolean onSearchRequested() {
        // We record whether the search dialog is open or not, so that if the screen gets
        // rotated, we can re-create it. See onRetainNonConfigurationInstance.
        mSearching = true;
        // This gets called when the user leaves the search dialog to go back to
        // the Launcher.
        mSearchManager.setOnCancelListener(new SearchManager.OnCancelListener() {
            public void onCancel() {
                mSearchManager.setOnCancelListener(null);
                mSearching = false;
                howToSelectLocationInfo();
            }
        });
        // I'm not sure if this ever gets called. But if it does, it should do
        // the same as the above.
        mSearchManager.setOnDismissListener(new SearchManager.OnDismissListener() {
            public void onDismiss() {
                mSearchManager.setOnDismissListener(null);
                mSearching = false;
                howToSelectLocationInfo();
            }
        });
        startSearch(mSearchTerm, true, null, false);
        return true;
    }

    /**
     * The method that performs the search, and acts on the results.
     * In practice, this just sets off the search in a separate thread and
     * display a progress dialog.
     * @param searchTerm The term entered in the search box
     */
    private void doSearch(String searchTerm) {
        // We save this so it can be passed back to the caller as the nick
        mSearchTerm = searchTerm;

        // Fire off a thread to do some work that we shouldn't do directly in
        // the UI thread. In this case, getting search results.
        Thread t = new Thread() {
            public void run() {
                mTries = 0;
                getSearchLocations();
                mHandler.post(mGotSearchResults);
                }
        };
        t.start();
        mProgressDialog = ProgressDialog.show(mContext,
                getText(R.string.search_progress),
                String.format(getString(R.string.search_progress_msg),
                        mSearchTerm), true, true);
    }

    /**
     * Display a dialog listing the search results
     * @param searchTerm The text entered in the search box
     */
    private void resultsDialog() {
        if (mResults == null) {
            // Create the dialog that informs the user that the search failed
            // because of bad data connection 
            Dialog badConnectionDlg = new AlertDialog.Builder(mContext)
                .setTitle(R.string.search_nodata_title)
                .setMessage(R.string.search_nodata_message)
                .setPositiveButton(R.string.alert_dialog_ok,
                                   new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        onSearchRequested();
                        }
                })
                .setIcon(R.drawable.icon)
                .create();
            badConnectionDlg.show();
            return;
        }
        if (mResults.size() == 0) {
            // Create the dialog that informs the user that the search failed
            // because no results were found
            Dialog noResultsDlg = new AlertDialog.Builder(mContext)
            .setTitle(R.string.search_noresults_title)
            .setMessage(R.string.search_noresults_message)
            .setPositiveButton(R.string.alert_dialog_ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int whichButton) {
                    onSearchRequested();
                    }
            })
            .setIcon(R.drawable.icon)
            .create();
            noResultsDlg.show();
            return;
        }
        // If neither of the others happened, then presumably we have a list of
        // results: display them in a pretty list
        mResultsDialog = new Dialog(mContext);
        mResultsDialog.setContentView(R.layout.search_list);

        ListView list = (ListView) mResultsDialog.findViewById(R.id.result_list);
        list.setAdapter(new SearchListAdapter(this));
        list.setOnItemClickListener(mResultClickListener);

        mResultsDialog.setTitle(R.string.searchresults_title);
        mResultsDialog.show();
    }

    /**
     * The listener that reacts to a click on one of the search results in the
     * list adaptor
     * Get the location from the item, move the map to it, and pop up a
     * confirmation dialog
     */
    private OnItemClickListener mResultClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position,
                long id) {
            double latitude = mResults.get(position).getLatitude();
            double longitude = mResults.get(position).getLongitude();
            moveMapTo(latitude, longitude);
            mDest = new GeoPoint((int) (latitude * 1E6),
                                 (int) (longitude * 1E6));
            selectedLocation();
            mResultsDialog.dismiss();
        }
    };

    /**
     * Search the map for text entered by the user
     * This is run in a separate thread, called from doSearch, so that we can
     * display a progress dialog on the screen
     */
    private void getSearchLocations() {
        mResults = null;
        try {
            mResults = mGeocoder.getFromLocationName(mSearchTerm, 5);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (mResults == null) {
            mTries++;
            if (mTries < MAX_TRIES) {
                Log.w(LOG_NAME, "No data connection, retrying search");
                Log.w(LOG_NAME, "Try " + (mTries + 1) + "/" + MAX_TRIES);
                // Just in case our geocoder is dodgy, recreate it
                mGeocoder = new Geocoder(mContext, Locale.getDefault());
                getSearchLocations();
            } else {
                mResults = null;
                mTries = 0;
                Log.wtf(LOG_NAME, "Couldn't retrieve locations: no data connection?");
            }
        }
    }

    /**
     * The list in the search results dialog
     * @author spookypeanut
     *
     */
    private class SearchListAdapter extends BaseAdapter {
        public SearchListAdapter(Context context) {
            mCurrLoc = getCurrentLocation();
        }

        @Override
        public int getCount() {
            return mResults.size();
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        /**
         * Inflate the view, and add the details of the given location into it
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row;

            if (null == convertView) {
                row = mInflater.inflate(R.layout.search_list_entry, null);
            } else {
                row = convertView;
            }
            Address result = mResults.get(position);
            TextView tv = (TextView) row.findViewById(R.id.searchListLine0);
            tv.setText(result.getAddressLine(0));
            tv = (TextView) row.findViewById(R.id.searchListLine1);
            tv.setText(result.getAddressLine(1));
            tv = (TextView) row.findViewById(R.id.searchListLine2);
            tv.setText(result.getAddressLine(2));
            tv = (TextView) row.findViewById(R.id.searchListDist);
            Location resultAsLoc = new Location("");
            resultAsLoc.setLatitude(result.getLatitude());
            resultAsLoc.setLongitude(result.getLongitude());
            tv.setText(uc.out(mCurrLoc.distanceTo(resultAsLoc)) + " away");

            return row;
        }
    }

    /**
     * The method that, at the end of the activity, passes the required data
     * back to caller
     */
    protected void returnLocation() {
        Intent i = new Intent();
        // We pass the search term back so that the caller can populate the
        // location name box with it
        i.putExtra("searchTerm", mSearchTerm);
        setResult(RESULT_OK, i.setAction(
                mDest.getLatitudeE6() / 1E6 + "," +
                mDest.getLongitudeE6() / 1E6));
        finish();
    }

    /**
     * The user has specified a location, we now ask them if they're sure that
     * this is the one they want. This method just starts a thread to retrieve
     * the addresses
     */
    public void selectedLocation() {
        final double latitude = mDest.getLatitudeE6()  / 1E6;
        final double longitude = mDest.getLongitudeE6() / 1E6;
        moveDestinationTo(latitude, longitude);
        Log.d(LOG_NAME, "Attempting geocoder lookup from " + latitude + ", " + longitude);

        // Fire off a thread to do some work that we shouldn't do directly in
        // the UI thread. In this case, geo-code a location.
        Thread t = new Thread() {
            public void run() {
                try {
                    mDestAddresses = mGeocoder.getFromLocation(latitude, longitude, 1);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                mHandler.post(mGotAddresses);
                }
        };
        t.start();
        // Create a progress dialog while we wait for the thread to finish
        mProgressDialog = ProgressDialog.show(mContext,
                getText(R.string.geocoder_progress),
                getText(R.string.geocoder_progress_msg),
                true, true);
    }

    /**
     * This method is called from the thread that retrieves the addresses
     * from the geocoder. It finishes the process started in selectedLocation()
     */
    private void gotDestinationAddress() {
        final double latitude = mDest.getLatitudeE6()  / 1E6;
        final double longitude = mDest.getLongitudeE6() / 1E6;

        // Prepare the various strings to display in the alert dialog
        String latlongMsg = "Latitude / Longitude:\n";
        latlongMsg += latitude + ", " + longitude;

        String addressMsg = "";
        if (mDestAddresses != null && mDestAddresses.size() > 0) {
            int i;
            for (i = 0; i < mDestAddresses.get(0).getMaxAddressLineIndex(); i++)
                addressMsg += mDestAddresses.get(0).getAddressLine(i) + "\n";
        } else {
            Log.wtf(LOG_NAME, "GeoCoder returned null");
            addressMsg += (String) getText(R.string.uselocation_nodata);

        }

        // Inflate the dialog, and put everything into it
        final View textEntryView =  mInflater.inflate(R.layout.select_location,
                                                      null);
        final TextView latlongBox = (TextView)textEntryView.
                                              findViewById(R.id.latlong_msg);
        final TextView addressBox = (TextView)textEntryView.
                                              findViewById(R.id.address_msg);
        latlongBox.setText(latlongMsg);
        addressBox.setText(addressMsg);

        DialogInterface.OnClickListener positiveListener = null;
        positiveListener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                returnLocation();
            }
        };
        AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(new ContextThemeWrapper(mContext, 
                                                R.style.GreenNatureDialog));
        builder.setTitle(R.string.select_location_dialog_title);
        builder.setView(textEntryView);
        builder.setIcon(R.drawable.icon);
        builder.setPositiveButton(R.string.alert_dialog_ok, positiveListener);
        builder.setNegativeButton(R.string.alert_dialog_cancel,
                                  new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                howToSelectLocationInfo();
            }
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * The class for the destination overlay on the map. Includes long-pressing
     * to select a location, and double-tapping to zoom in
     */
    public class DestOverlay extends Overlay implements OnGestureListener, 
                                                        OnDoubleTapListener{
        private GestureDetector gestureDetector;

        Context oContext;
        double mLat;
        double mLon;
        double mRadius;

         public DestOverlay(Context context,
                            double lat, double lon,
                            double radius) {
                oContext = context;
                mLat = lat;
                mLon = lon;
                mRadius = radius;
                gestureDetector = new GestureDetector(this);
                gestureDetector.setOnDoubleTapListener((OnDoubleTapListener) this);
         }

         /**
          * How to draw the overlay.
          * This includes both the marker and the circle denoting the radius
          */
         public void draw(Canvas canvas, MapView mapView, boolean shadow) {
             super.draw(canvas, mapView, shadow);
             Resources res = oContext.getResources();
             float[] result = new float[1];

             // Find the distance apart that one degree of longitude gives at
             // this latitude
             Location.distanceBetween(mLat, mLon, mLat, mLon + 1, result);
             float longitudeLineDistance = result[0];

             // Create two GeoPoints: one at the location, another at *radius*
             // units away from the location
             GeoPoint geo = new GeoPoint((int) (mLat *1e6), (int)(mLon * 1e6));
             GeoPoint leftGeo = new GeoPoint((int)(mLat * 1E6), (int)((mLon - mRadius / longitudeLineDistance) * 1E6));

             // Get those two GeoPoints in pixels as Points
             Projection projection = mapView.getProjection();
             Point pt = new Point();
             Point left = new Point();
             projection.toPixels(leftGeo, left);
             projection.toPixels(geo, pt);

             // The distance these two points are away from each other is the
             // radius that we should draw the circle, in pixels
             float circleRadius = (float) pt.x - (float) left.x;

             // Draw the circle first
             Paint circlePaint = new Paint();
             circlePaint.setColor(res.getColor(R.color.overlaycolor));
             circlePaint.setAntiAlias(true);
             circlePaint.setStyle(Paint.Style.FILL);
             canvas.drawCircle((float)pt.x, (float)pt.y, circleRadius, circlePaint);

             // Then draw the pointer on top
             Bitmap bitmap;
             bitmap = BitmapFactory.decodeResource(res, R.drawable.pointer);
             // The "hot-spot" is in the middle horizontally...
             float drawablex = pt.x - bitmap.getWidth() / 2;
             // ... and at the bottom vertically
             float drawabley = pt.y - bitmap.getHeight();
             canvas.drawBitmap(bitmap, drawablex, drawabley, new Paint());
            }

         @Override
         public boolean onDoubleTap(MotionEvent e) {
             // REF#0024
             MapController mc = mapView.getController();
             int x = (int) e.getX();
             int y = (int) e.getY();
             mc.zoomInFixing(x, y);
             return true;
         }

         @Override
         public boolean onTouchEvent(MotionEvent event, MapView mv) {
             mapView = mv;
             if (gestureDetector.onTouchEvent(event)) {
                 return true;
             }
             return false;
         }

         /**
          * If a long press is heard, select the location
          */
         @Override
         public void onLongPress(MotionEvent event) {
             mDest = mapView.getProjection().fromPixels(
                     (int) event.getX(),
                     (int) event.getY());
             selectedLocation();
         }

         /**
          * These methods are all required to implement gesture listener, but
          *  don't need them
          */
         @Override
         public boolean onDown(MotionEvent e) {
             return false;
         }

         @Override
         public boolean onFling(MotionEvent e1, MotionEvent e2,
                                float velocityX, float velocityY) {
             return false;
         }

         @Override
         public boolean onScroll(MotionEvent e1, MotionEvent e2,
                                 float distanceX, float distanceY) {
             return false;
         }

         @Override
         public void onShowPress(MotionEvent e) {
         }

         @Override
         public boolean onSingleTapUp(MotionEvent e) {
             return false;
         }


        /* (non-Javadoc)
         * @see android.view.GestureDetector.OnDoubleTapListener#onSingleTapConfirmed(android.view.MotionEvent)
         */
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return false;
        }


        /* (non-Javadoc)
         * @see android.view.GestureDetector.OnDoubleTapListener#onDoubleTapEvent(android.view.MotionEvent)
         */
        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            return false;
        }
    }
}
