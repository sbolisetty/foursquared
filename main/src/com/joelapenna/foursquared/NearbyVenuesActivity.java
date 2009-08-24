/**
 * Copyright 2009 Joe LaPenna
 */

package com.joelapenna.foursquared;

import com.joelapenna.foursquare.Foursquare;
import com.joelapenna.foursquare.error.FoursquareException;
import com.joelapenna.foursquare.types.City;
import com.joelapenna.foursquare.types.Group;
import com.joelapenna.foursquare.types.Venue;
import com.joelapenna.foursquared.Foursquared.LocationListener;
import com.joelapenna.foursquared.providers.VenueQuerySuggestionsProvider;
import com.joelapenna.foursquared.util.NotificationsUtil;
import com.joelapenna.foursquared.widget.SeparatedListAdapter;
import com.joelapenna.foursquared.widget.VenueListAdapter;

import android.app.Activity;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.SearchRecentSuggestions;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;

import java.io.IOException;
import java.util.Observable;

/**
 * @author Joe LaPenna (joe@joelapenna.com)
 */
public class NearbyVenuesActivity extends Activity {
    static final String TAG = "NearbyVenuesActivity";
    static final boolean DEBUG = FoursquaredSettings.DEBUG;

    public static final String QUERY_NEARBY = null;
    public static SearchResultsObservable searchResultsObservable;

    private static final int MENU_REFRESH = 0;
    private static final int MENU_ADD_VENUE = 1;
    private static final int MENU_SEARCH = 2;
    private static final int MENU_MYINFO = 3;

    private LocationManager mLocationManager;
    private LocationListener mLocationListener;

    private SearchTask mSearchTask;
    private SearchHolder mSearchHolder = new SearchHolder();

    private ListView mListView;
    private LinearLayout mEmpty;
    private TextView mEmptyText;
    private ProgressBar mEmptyProgress;
    private SeparatedListAdapter mListAdapter;

    private BroadcastReceiver mLoggedInReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) Log.d(TAG, "onReceive: " + intent);
            finish();
        }
    };
    private boolean mIsShortcutPicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.search_list_activity);
        registerReceiver(mLoggedInReceiver, new IntentFilter(Foursquared.INTENT_ACTION_LOGGED_OUT));

        mLocationListener = ((Foursquared)getApplication()).getLocationListener();
        mLocationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);

        searchResultsObservable = new SearchResultsObservable();

        initListViewAdapter();

        // Watch to see if we've been called as a shortcut intent.
        mIsShortcutPicker = Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction());

        if (getLastNonConfigurationInstance() != null) {
            if (DEBUG) Log.d(TAG, "Restoring state.");
            SearchHolder holder = (SearchHolder)getLastNonConfigurationInstance();
            if (holder.results == null) {
                executeSearchTask(holder.query);
            } else {
                mSearchHolder.query = holder.query;
                setSearchResults(holder.results);
                putSearchResultsInAdapter(holder.results);
            }
        } else {
            onNewIntent(getIntent());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mLoggedInReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();
        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                LocationListener.LOCATION_UPDATE_MIN_TIME,
                LocationListener.LOCATION_UPDATE_MIN_DISTANCE, mLocationListener);
        mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                LocationListener.LOCATION_UPDATE_MIN_TIME,
                LocationListener.LOCATION_UPDATE_MIN_DISTANCE, mLocationListener);
    }

    @Override
    public void onPause() {
        super.onPause();
        mLocationManager.removeUpdates(mLocationListener);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mSearchTask != null) {
            mSearchTask.cancel(true);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, R.string.search_label) //
                .setIcon(android.R.drawable.ic_search_category_default) //
                .setAlphabeticShortcut(SearchManager.MENU_KEY);
        menu.add(Menu.NONE, MENU_REFRESH, Menu.NONE, R.string.refresh_label) //
                .setIcon(R.drawable.ic_menu_refresh);
        menu.add(Menu.NONE, MENU_ADD_VENUE, Menu.NONE, R.string.add_venue_label) //
                .setIcon(android.R.drawable.ic_menu_add);
        menu.add(Menu.NONE, MENU_MYINFO, Menu.NONE, R.string.myinfo_label) //
                .setIcon(R.drawable.ic_menu_myinfo);
        Foursquared.addPreferencesToMenu(this, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SEARCH:
                Intent intent = new Intent(NearbyVenuesActivity.this, SearchVenuesActivity.class);
                intent.setAction(Intent.ACTION_SEARCH);
                startActivity(intent);
                return true;
            case MENU_REFRESH:
                executeSearchTask(null);
                return true;
            case MENU_ADD_VENUE:
                startActivity(new Intent(NearbyVenuesActivity.this, AddVenueActivity.class));
                return true;
            case MENU_MYINFO:
                startActivity(new Intent(NearbyVenuesActivity.this, UserActivity.class));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onNewIntent(Intent intent) {
        if (DEBUG) Log.d(TAG, "New Intent: " + intent);
        if (intent == null) {
            if (DEBUG) Log.d(TAG, "No intent to search, querying default.");
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            if (DEBUG) Log.d(TAG, "onNewIntent received search intent and saving.");
            SearchRecentSuggestions suggestions = new SearchRecentSuggestions(this,
                    VenueQuerySuggestionsProvider.AUTHORITY, VenueQuerySuggestionsProvider.MODE);
            suggestions.saveRecentQuery(intent.getStringExtra(SearchManager.QUERY), null);

        }
        executeSearchTask(intent.getStringExtra(SearchManager.QUERY));
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mSearchHolder;
    }

    public void putSearchResultsInAdapter(Group searchResults) {
        mListAdapter.clear();
        int groupCount = searchResults.size();
        for (int groupsIndex = 0; groupsIndex < groupCount; groupsIndex++) {
            Group group = (Group)searchResults.get(groupsIndex);
            if (group.size() > 0) {
                VenueListAdapter groupAdapter = new VenueListAdapter(this, group);
                if (DEBUG) Log.d(TAG, "Adding Section: " + group.getType());
                mListAdapter.addSection(group.getType(), groupAdapter);
            }
        }
        mListAdapter.notifyDataSetInvalidated();
    }

    public void setSearchResults(Group searchResults) {
        if (DEBUG) Log.d(TAG, "Setting search results.");
        mSearchHolder.results = searchResults;
        searchResultsObservable.notifyObservers();
    }

    void executeSearchTask(String query) {
        if (DEBUG) Log.d(TAG, "sendQuery()");
        mSearchHolder.query = query;
        // not going through set* because we don't want to notify search result
        // observers.
        mSearchHolder.results = null;

        // If a task is already running, don't start a new one.
        if (mSearchTask != null && mSearchTask.getStatus() != AsyncTask.Status.FINISHED) {
            if (DEBUG) Log.d(TAG, "Query already running attempting to cancel: " + mSearchTask);
            if (!mSearchTask.cancel(true) && !mSearchTask.isCancelled()) {
                if (DEBUG) Log.d(TAG, "Unable to cancel search? Notifying the user.");
                Toast.makeText(this, "A search is already in progress.", Toast.LENGTH_SHORT);
                return;
            }
        }
        mSearchTask = (SearchTask)new SearchTask().execute();
    }

    void startItemActivity(Venue venue) {
        Intent intent = new Intent(NearbyVenuesActivity.this, VenueActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(Foursquared.EXTRA_VENUE_ID, venue.getId());
        startActivity(intent);
    }

    private void ensureSearchResults() {
        if (mListAdapter.getCount() > 0) {
            mEmpty.setVisibility(LinearLayout.GONE);
        } else {
            mEmptyText.setText("No search results.");
            mEmptyProgress.setVisibility(LinearLayout.GONE);
            mEmpty.setVisibility(LinearLayout.VISIBLE);
        }
    }

    private void ensureTitle(boolean finished) {
        if (finished) {
            if (mSearchHolder.query == QUERY_NEARBY) {
                setTitle("Nearby - Foursquare");
            } else {
                setTitle(mSearchHolder.query + " - Foursquare");
            }
        } else {
            if (mSearchHolder.query == QUERY_NEARBY) {
                setTitle("Searching Nearby - Foursquare");
            } else {
                setTitle("Searching \"" + mSearchHolder.query + "\" - Foursquare");
            }
        }
    }

    private void initListViewAdapter() {
        if (mListView != null) {
            throw new IllegalStateException("Trying to initialize already initialized ListView");
        }
        mEmpty = (LinearLayout)findViewById(R.id.empty);
        mEmptyText = (TextView)findViewById(R.id.emptyText);
        mEmptyProgress = (ProgressBar)findViewById(R.id.emptyProgress);

        mListView = (ListView)findViewById(R.id.list);
        mListAdapter = new SeparatedListAdapter(this);

        mListView.setAdapter(mListAdapter);
        mListView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Venue venue = (Venue)parent.getAdapter().getItem(position);
                if (mIsShortcutPicker) {
                    setupShortcut(venue);
                    finish();
                } else {
                    startItemActivity(venue);
                }
            }
        });
    }

    protected void setupShortcut(Venue venue) {
        // First, set up the shortcut intent. For this example, we simply create an intent that
        // will bring us directly back to this activity. A more typical implementation would use a
        // data Uri in order to display a more specific result, or a custom action in order to
        // launch a specific operation.

        Intent shortcutIntent = new Intent(Intent.ACTION_MAIN);
        shortcutIntent.setClassName(this, VenueActivity.class.getName());
        shortcutIntent.putExtra(Foursquared.EXTRA_VENUE_ID, venue.getId());

        // Then, set up the container intent (the response to the caller)
        Intent intent = new Intent();
        intent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
        intent.putExtra(Intent.EXTRA_SHORTCUT_NAME, venue.getName());
        Parcelable iconResource = Intent.ShortcutIconResource.fromContext(this,
                R.drawable.venue_shortcut_icon);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE, iconResource);

        // Now, return the result to the launcher

        setResult(RESULT_OK, intent);
    }

    private class SearchTask extends AsyncTask<Void, Void, Group> {

        private static final int METERS_PER_MILE = 1609;

        private Exception mReason = null;

        @Override
        public void onPreExecute() {
            if (DEBUG) Log.d(TAG, "SearchTask: onPreExecute()");
            setProgressBarIndeterminateVisibility(true);
            ensureTitle(false);
        }

        @Override
        public Group doInBackground(Void... params) {
            try {
                return search();
            } catch (Exception e) {
                mReason = e;
            }
            return null;
        }

        @Override
        public void onPostExecute(Group groups) {
            try {
                if (groups == null) {
                    NotificationsUtil.ToastReasonForFailure(NearbyVenuesActivity.this, mReason);
                } else {
                    setSearchResults(groups);
                    putSearchResultsInAdapter(groups);
                }

            } finally {
                setProgressBarIndeterminateVisibility(false);
                ensureTitle(true);
                ensureSearchResults();
            }
        }

        public Group search() throws FoursquareException, IOException {
            Location location = mLocationListener.getLastKnownLocation();
            Foursquare foursquare = ((Foursquared)getApplication()).getFoursquare();
            String geolat;
            String geolong;
            int radius;
            if (location == null) {
                // Foursquare requires a lat, lng for a venue search, so we have to pull it from the
                // server if we cannot determine it locally.
                City city = foursquare.user(null, false, false).getCity();
                geolat = String.valueOf(city.getGeolat());
                geolong = String.valueOf(city.getGeolong());
                radius = 1;
            } else {
                if (DEBUG) Log.d(TAG, "Searching with location: " + location);
                geolat = String.valueOf(location.getLatitude());
                geolong = String.valueOf(location.getLongitude());
                if (location.hasAccuracy()) {
                    radius = (int)Math.round(location.getAccuracy() / (double)METERS_PER_MILE);
                } else {
                    radius = 1;
                }
            }
            return foursquare.venues(geolat, geolong, mSearchHolder.query, radius, 30);
        }
    }

    private static class SearchHolder {
        Group results;
        String query;
    }

    class SearchResultsObservable extends Observable {

        public void notifyObservers(Object data) {
            setChanged();
            super.notifyObservers(data);
        }

        public Group getSearchResults() {
            return mSearchHolder.results;
        }

        public String getQuery() {
            return mSearchHolder.query;
        }
    };
}