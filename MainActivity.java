package com.fuellens.application;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.appcompat.app.AppCompatActivity;


import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;
import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.*;
import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;
import com.google.android.gms.tasks.Task;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.widget.Autocomplete;
import com.google.android.libraries.places.widget.AutocompleteActivity;
import com.google.android.libraries.places.widget.AutocompleteSupportFragment;
import com.google.android.libraries.places.widget.listener.PlaceSelectionListener;
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode;
import com.google.maps.android.PolyUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

public class MainActivity extends AppCompatActivity implements OnMapReadyCallback {

    private static final int PERMISSION_CODE = 1;
    private static final int AUTOCOMPLETE_REQUEST_CODE = 1002;
    private static final int LOCATION_SETTINGS_REQUEST = 1001;
    private static final String TAG = "FuelFinderApp";

    private GoogleMap mMap;
    private FusedLocationProviderClient fusedLocationProviderClient;
    private LatLng userLatLng;
    private Marker userMarker;
    private Circle userCircle;
    private ImageView btnMyLocation, btnSearch, btnRefresh;
    private Button btnLogin, btnSignup;
    private AutocompleteSupportFragment autocompleteFragment;
    private RequestQueue requestQueue;
    private List<Marker> fuelStationMarkers = new ArrayList<>();
    private Polyline currentRoute;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Places API
        if (!Places.isInitialized()) {
            Places.initialize(getApplicationContext(), getString(R.string.MAPS_API_KEY));
        }

        // Initialize components
        initializeViews();
        initializeLocationServices();
        initializeMap();
        setupClickListeners();
        setupAutoComplete();
    }

    private void initializeViews() {
        btnMyLocation = findViewById(R.id.btnMyLocation);
        btnSearch = findViewById(R.id.btnSearch);
        btnRefresh = findViewById(R.id.btnRefresh);
        btnLogin = findViewById(R.id.btnLogin);
        btnSignup = findViewById(R.id.btnSignup);
        requestQueue = Volley.newRequestQueue(this);
    }

    private void initializeLocationServices() {
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
    }

    private void initializeMap() {
        SupportMapFragment mapFragment = (SupportMapFragment)
                getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    private void setupClickListeners() {
        btnMyLocation.setOnClickListener(v -> {
            if (userLatLng != null) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 15));
            } else {
                requestLocation();
            }
        });

        btnSearch.setOnClickListener(v -> openPlaceAutocomplete());

        btnRefresh.setOnClickListener(v -> {
            clearMarkersAndRoutes();
            if (userLatLng != null) {
                getNearbyPumps(userLatLng);
                Toast.makeText(this, "Refreshing fuel stations...", Toast.LENGTH_SHORT).show();
            } else {
                requestLocation();
            }
        });

        btnLogin.setOnClickListener(v -> {
            // TODO: Implement login functionality
            Toast.makeText(this, "Login feature coming soon!", Toast.LENGTH_SHORT).show();
        });

        btnSignup.setOnClickListener(v -> {
            // TODO: Implement signup functionality
            Toast.makeText(this, "Signup feature coming soon!", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupAutoComplete() {
        autocompleteFragment = (AutocompleteSupportFragment)
                getSupportFragmentManager().findFragmentById(R.id.autocomplete_fragment);

        if (autocompleteFragment != null) {
            autocompleteFragment.setPlaceFields(Arrays.asList(
                    Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS));

            autocompleteFragment.setHint("Search for a location...");

            autocompleteFragment.setOnPlaceSelectedListener(new PlaceSelectionListener() {
                @Override
                public void onPlaceSelected(@NonNull Place place) {
                    Log.d(TAG, "Place selected: " + place.getName());
                    if (place.getLatLng() != null) {
                        LatLng selectedLocation = place.getLatLng();
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(selectedLocation, 15));
                        getNearbyPumps(selectedLocation);

                        // Add a temporary marker for searched location
                        mMap.addMarker(new MarkerOptions()
                                .position(selectedLocation)
                                .title(place.getName())
                                .snippet(place.getAddress())
                                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                    }
                }

                @Override
                public void onError(@NonNull com.google.android.gms.common.api.Status status) {
                    Log.e(TAG, "Place selection error: " + status.getStatusMessage());
                    Toast.makeText(MainActivity.this, "Error selecting place", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void openPlaceAutocomplete() {
        List<Place.Field> fields = Arrays.asList(
                Place.Field.ID, Place.Field.NAME, Place.Field.LAT_LNG, Place.Field.ADDRESS);

        Intent intent = new Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields)
                .build(this);
        startActivityForResult(intent, AUTOCOMPLETE_REQUEST_CODE);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.mMap = googleMap;

        // Configure map settings
        mMap.getUiSettings().setMyLocationButtonEnabled(false);
        mMap.getUiSettings().setZoomControlsEnabled(true);
        mMap.getUiSettings().setCompassEnabled(true);
        mMap.getUiSettings().setMapToolbarEnabled(true);

        // Set map type
        mMap.setMapType(GoogleMap.MAP_TYPE_NORMAL);

        // Enable location if permission granted
        enableLocation();

        // Set map click listener
        mMap.setOnMapClickListener(latLng -> {
            clearMarkersAndRoutes();
            getNearbyPumps(latLng);

            // Add a marker for clicked location
            mMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title("Selected Location")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)));
        });

        // Set info window click listener
        mMap.setOnInfoWindowClickListener(marker -> {
            LatLng destination = marker.getPosition();
            if (userLatLng != null && !marker.equals(userMarker)) {
                drawRoute(userLatLng, destination);
            }
        });
    }

    private void enableLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, PERMISSION_CODE);
        } else {
            requestLocation();
        }
    }

    @SuppressLint("MissingPermission")
    private void requestLocation() {
        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setInterval(10000)
                .setFastestInterval(5000)
                .setMaxWaitTime(15000);

        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest)
                .setAlwaysShow(true);

        Task<LocationSettingsResponse> task = LocationServices.getSettingsClient(this)
                .checkLocationSettings(builder.build());

        task.addOnSuccessListener(locationSettingsResponse -> {
            Log.d(TAG, "Location settings satisfied");
            getDeviceLocation();
        });

        task.addOnFailureListener(e -> {
            if (e instanceof ResolvableApiException) {
                try {
                    ResolvableApiException resolvable = (ResolvableApiException) e;
                    resolvable.startResolutionForResult(MainActivity.this, LOCATION_SETTINGS_REQUEST);
                } catch (Exception ex) {
                    Log.e(TAG, "Error starting resolution for location settings", ex);
                }
            } else {
                Toast.makeText(this, "Location settings cannot be satisfied", Toast.LENGTH_LONG).show();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void getDeviceLocation() {
        fusedLocationProviderClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        userLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                        showUserLocation(userLatLng);
                        Log.d(TAG, "User location: " + userLatLng.toString());
                    } else {
                        Log.d(TAG, "Last known location is null, requesting current location");
                        requestCurrentLocation();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get last location", e);
                    Toast.makeText(this, "Unable to get current location", Toast.LENGTH_SHORT).show();
                });
    }

    @SuppressLint("MissingPermission")
    private void requestCurrentLocation() {
        LocationRequest locationRequest = LocationRequest.create()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setNumUpdates(1)
                .setInterval(0);

        fusedLocationProviderClient.requestLocationUpdates(locationRequest,
                new LocationCallback() {
                    @Override
                    public void onLocationResult(@NonNull LocationResult locationResult) {
                        Location location = locationResult.getLastLocation();
                        if (location != null) {
                            userLatLng = new LatLng(location.getLatitude(), location.getLongitude());
                            showUserLocation(userLatLng);
                        }
                        fusedLocationProviderClient.removeLocationUpdates(this);
                    }
                }, getMainLooper());
    }

    private void showUserLocation(LatLng latLng) {
        if (mMap == null) return;

        // Remove existing user marker and circle
        if (userMarker != null) userMarker.remove();
        if (userCircle != null) userCircle.remove();

        // Create custom marker icon for user location
        try {
            Bitmap icon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_mylocation);
            if (icon != null) {
                // Resize icon if needed
                Bitmap resizedIcon = Bitmap.createScaledBitmap(icon, 100, 100, false);
                userMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title("Your Location")
                        .snippet("You are here")
                        .icon(BitmapDescriptorFactory.fromBitmap(resizedIcon)));
            } else {
                // Fallback to default marker
                userMarker = mMap.addMarker(new MarkerOptions()
                        .position(latLng)
                        .title("Your Location")
                        .snippet("You are here")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating user marker", e);
            userMarker = mMap.addMarker(new MarkerOptions()
                    .position(latLng)
                    .title("Your Location")
                    .snippet("You are here"));
        }

        // Add circle to show search radius
        userCircle = mMap.addCircle(new CircleOptions()
                .center(latLng)
                .radius(5000) // 5km radius
                .strokeColor(0x550197F5)
                .fillColor(0x115197F5)
                .strokeWidth(3));

        // Move camera to user location
        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 14));

        // Search for nearby fuel pumps
        getNearbyPumps(latLng);
    }

    private void getNearbyPumps(LatLng latLng) {
        String url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json?" +
                "location=" + latLng.latitude + "," + latLng.longitude +
                "&radius=5000" +
                "&type=gas_station" +
                "&key=" + getString(R.string.MAPS_API_KEY);

        StringRequest request = new StringRequest(Request.Method.GET, url,
                response -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        JSONArray results = jsonResponse.getJSONArray("results");

                        // Clear existing fuel station markers
                        clearFuelStationMarkers();

                        Log.d(TAG, "Found " + results.length() + " fuel stations");

                        for (int i = 0; i < results.length(); i++) {
                            JSONObject place = results.getJSONObject(i);
                            String name = place.getString("name");
                            String vicinity = place.optString("vicinity", "");
                            double rating = place.optDouble("rating", 0.0);

                            JSONObject geometry = place.getJSONObject("geometry");
                            JSONObject location = geometry.getJSONObject("location");
                            double lat = location.getDouble("lat");
                            double lng = location.getDouble("lng");

                            LatLng stationLocation = new LatLng(lat, lng);

                            // Create marker with custom icon
                            Marker marker = createFuelStationMarker(stationLocation, name, vicinity, rating);
                            if (marker != null) {
                                fuelStationMarkers.add(marker);
                            }
                        }

                        if (results.length() == 0) {
                            Toast.makeText(this, "No fuel stations found within 5km", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this, "Found " + results.length() + " fuel stations", Toast.LENGTH_SHORT).show();
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing fuel stations response", e);
                        Toast.makeText(this, "Error loading fuel stations", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    Log.e(TAG, "Network error while fetching fuel stations", error);
                    Toast.makeText(this, "Network error. Please check your connection.", Toast.LENGTH_SHORT).show();
                });

        requestQueue.add(request);
    }

    private Marker createFuelStationMarker(LatLng location, String name, String vicinity, double rating) {
        try {
            Bitmap fuelIcon = BitmapFactory.decodeResource(getResources(), R.drawable.fuel_icon);
            String snippet = vicinity;
            if (rating > 0) {
                snippet += "\nRating: " + String.format("%.1f", rating) + "⭐";
            }

            MarkerOptions markerOptions = new MarkerOptions()
                    .position(location)
                    .title(name)
                    .snippet(snippet);

            if (fuelIcon != null) {
                Bitmap resizedIcon = Bitmap.createScaledBitmap(fuelIcon, 80, 80, false);
                markerOptions.icon(BitmapDescriptorFactory.fromBitmap(resizedIcon));
            } else {
                markerOptions.icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED));
            }

            Marker marker = mMap.addMarker(markerOptions);
            marker.setTag(location);
            return marker;

        } catch (Exception e) {
            Log.e(TAG, "Error creating fuel station marker", e);
            return null;
        }
    }

    private void clearFuelStationMarkers() {
        for (Marker marker : fuelStationMarkers) {
            marker.remove();
        }
        fuelStationMarkers.clear();
    }

    private void clearMarkersAndRoutes() {
        clearFuelStationMarkers();
        if (currentRoute != null) {
            currentRoute.remove();
            currentRoute = null;
        }
        mMap.clear();
        // Re-add user location if available
        if (userLatLng != null) {
            showUserLocation(userLatLng);
        }
    }

    private void drawRoute(LatLng origin, LatLng destination) {
        String url = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=" + origin.latitude + "," + origin.longitude +
                "&destination=" + destination.latitude + "," + destination.longitude +
                "&mode=driving" +
                "&key=" + getString(R.string.MAPS_API_KEY);

        StringRequest request = new StringRequest(Request.Method.GET, url,
                response -> {
                    try {
                        JSONObject jsonResponse = new JSONObject(response);
                        JSONArray routes = jsonResponse.getJSONArray("routes");

                        if (routes.length() > 0) {
                            // Remove existing route
                            if (currentRoute != null) {
                                currentRoute.remove();
                            }

                            JSONObject route = routes.getJSONObject(0);
                            String encodedPolyline = route.getJSONObject("overview_polyline").getString("points");

                            // Get route info
                            JSONArray legs = route.getJSONArray("legs");
                            if (legs.length() > 0) {
                                JSONObject leg = legs.getJSONObject(0);
                                String distance = leg.getJSONObject("distance").getString("text");
                                String duration = leg.getJSONObject("duration").getString("text");

                                Toast.makeText(this, "Distance: " + distance + ", Duration: " + duration,
                                        Toast.LENGTH_LONG).show();
                            }

                            // Decode and draw polyline
                            List<LatLng> path = PolyUtil.decode(encodedPolyline);
                            currentRoute = mMap.addPolyline(new PolylineOptions()
                                    .addAll(path)
                                    .color(0xFF1976D2)
                                    .width(8)
                                    .pattern(Arrays.asList(new Dash(10), new Gap(5))));

                            Log.d(TAG, "Route drawn successfully");
                        } else {
                            Toast.makeText(this, "No route found", Toast.LENGTH_SHORT).show();
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing directions response", e);
                        Toast.makeText(this, "Error drawing route", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    Log.e(TAG, "Network error while fetching directions", error);
                    Toast.makeText(this, "Network error while getting directions", Toast.LENGTH_SHORT).show();
                });

        requestQueue.add(request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == LOCATION_SETTINGS_REQUEST) {
            if (resultCode == RESULT_OK) {
                requestLocation();
            } else {
                Toast.makeText(this, "Location access is required for this app", Toast.LENGTH_LONG).show();
            }
        }

        if (requestCode == AUTOCOMPLETE_REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Place place = Autocomplete.getPlaceFromIntent(data);
                if (place.getLatLng() != null) {
                    LatLng selectedLocation = place.getLatLng();
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(selectedLocation, 15));
                    getNearbyPumps(selectedLocation);

                    // Add marker for searched place
                    mMap.addMarker(new MarkerOptions()
                            .position(selectedLocation)
                            .title(place.getName())
                            .snippet(place.getAddress())
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)));
                }
            } else if (resultCode == AutocompleteActivity.RESULT_ERROR && data != null) {
                com.google.android.gms.common.api.Status status = Autocomplete.getStatusFromIntent(data);
                Log.e(TAG, "Autocomplete error: " + status.getStatusMessage());
                Toast.makeText(this, "Search error", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Location permission granted");
                requestLocation();
            } else {
                Log.d(TAG, "Location permission denied");
                Toast.makeText(this, "Location permission is required to find nearby fuel stations",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (requestQueue != null) {
            requestQueue.cancelAll(TAG);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop location updates to save battery
        if (fusedLocationProviderClient != null) {
            fusedLocationProviderClient.removeLocationUpdates(new LocationCallback() {});
        }
    }
}