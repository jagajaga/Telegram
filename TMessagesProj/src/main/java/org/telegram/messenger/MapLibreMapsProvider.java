package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.PointF;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.util.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;
import org.maplibre.android.maps.UiSettings;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;
import org.maplibre.geojson.Polygon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Vector maps through MapLibre, for builds that are not signed with Telegram's certificate and so
 * cannot use its Google Maps key. Tiles and styles come from CARTO's public OpenStreetMap-based
 * basemaps (no key): Voyager by day, Dark Matter at night, both crisp at any density.
 * <p>
 * Everything the UI asks of {@link IMapsProvider} is mapped: markers are a symbol layer (any
 * anchor, rotation, click hit-testing on the rendered icon), circles are a fill plus a solid or
 * dashed line layer, camera padding, move reasons and idle callbacks are MapLibre's own. Map
 * types are ignored: there is no satellite layer.
 */
public class MapLibreMapsProvider implements IMapsProvider {

    private static final String STYLE_LIGHT = "https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json";
    private static final String STYLE_DARK = "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json";
    private static final double MIN_ZOOM = 2;
    private static final int DEFAULT_ANIMATION_MS = 300;

    private static final String MARKERS_SOURCE = "tg-markers";
    private static final String MARKERS_LAYER = "tg-markers-layer";
    private static final String CIRCLES_SOURCE = "tg-circles";
    private static final String CIRCLES_FILL_LAYER = "tg-circles-fill";
    private static final String CIRCLES_LINE_LAYER = "tg-circles-line";
    private static final String CIRCLES_DASHED_LAYER = "tg-circles-dashed";

    @Override
    public void initializeMaps(Context context) {
        try {
            MapLibre.getInstance(context.getApplicationContext());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        initializeMaps(context);
        return new LibreMapView(context);
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions() {
        return new LibreMarkerOptions();
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new LibreCircleOptions();
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new LibreLatLngBoundsBuilder();
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) {
        return new LibreCameraUpdate(CameraUpdateFactory.newLatLng(toLatLng(latLng)));
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) {
        return new LibreCameraUpdate(CameraUpdateFactory.newLatLngZoom(toLatLng(latLng), zoom));
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return new LibreCameraUpdate(CameraUpdateFactory.newLatLngBounds(((LibreLatLngBounds) bounds).bounds, padding));
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return new LibreMapStyleOptions(resId == R.raw.mapstyle_night);
    }

    @Override
    public String getMapsAppPackageName() {
        return "com.google.android.apps.maps";
    }

    @Override
    public int getInstallMapsString() {
        return R.string.InstallGoogleMaps;
    }

    private static org.maplibre.android.geometry.LatLng toLatLng(LatLng latLng) {
        return new org.maplibre.android.geometry.LatLng(latLng.latitude, latLng.longitude);
    }

    private static LatLng fromLatLng(org.maplibre.android.geometry.LatLng latLng) {
        return new LatLng(latLng.getLatitude(), latLng.getLongitude());
    }

    private static String rgba(int color) {
        return String.format(Locale.US, "rgba(%d,%d,%d,%.3f)", (color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff, ((color >>> 24) & 0xff) / 255f);
    }

    /* map view */

    public final class LibreMapView implements IMapView {
        private final MapView mapView;
        private ITouchInterceptor dispatchInterceptor;
        private ITouchInterceptor interceptInterceptor;
        private Runnable onLayoutListener;
        private LibreMapImpl map;
        private boolean created;
        private boolean started;

        private LibreMapView(Context context) {
            MapLibreMapOptions options = MapLibreMapOptions.createFromAttributes(context)
                .textureMode(true)
                .compassEnabled(false)
                .logoEnabled(false)
                .rotateGesturesEnabled(false)
                .tiltGesturesEnabled(false);
            mapView = new MapView(context, options) {
                @Override
                public boolean dispatchTouchEvent(MotionEvent ev) {
                    if (dispatchInterceptor != null) {
                        return dispatchInterceptor.onInterceptTouchEvent(ev, super::dispatchTouchEvent);
                    }
                    return super.dispatchTouchEvent(ev);
                }

                @Override
                public boolean onInterceptTouchEvent(MotionEvent ev) {
                    if (interceptInterceptor != null) {
                        return interceptInterceptor.onInterceptTouchEvent(ev, super::onInterceptTouchEvent);
                    }
                    return super.onInterceptTouchEvent(ev);
                }

                @Override
                protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                    super.onLayout(changed, left, top, right, bottom);
                    if (onLayoutListener != null) {
                        onLayoutListener.run();
                    }
                }
            };
        }

        private void ensureCreated() {
            if (!created) {
                created = true;
                mapView.onCreate(null);
            }
        }

        @Override
        public View getView() {
            return mapView;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            ensureCreated();
            if (map != null) {
                AndroidUtilities.runOnUIThread(() -> callback.accept(map));
                return;
            }
            mapView.getMapAsync(libreMap -> {
                if (map == null) {
                    map = new LibreMapImpl(mapView, libreMap);
                }
                callback.accept(map);
            });
        }

        @Override
        public void onCreate(Bundle savedInstance) {
            ensureCreated();
        }

        @Override
        public void onResume() {
            ensureCreated();
            if (!started) {
                started = true;
                mapView.onStart();
            }
            mapView.onResume();
        }

        @Override
        public void onPause() {
            mapView.onPause();
            if (started) {
                started = false;
                mapView.onStop();
            }
        }

        @Override
        public void onDestroy() {
            if (map != null) {
                map.destroy();
            }
            if (started) {
                started = false;
                mapView.onStop();
            }
            if (created) {
                mapView.onDestroy();
            }
        }

        @Override
        public void onLowMemory() {
            mapView.onLowMemory();
        }

        @Override
        public void setOnDispatchTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            dispatchInterceptor = touchInterceptor;
        }

        @Override
        public void setOnInterceptTouchEventInterceptor(ITouchInterceptor touchInterceptor) {
            interceptInterceptor = touchInterceptor;
        }

        @Override
        public void setOnLayoutListener(Runnable callback) {
            onLayoutListener = callback;
        }
    }

    /* map */

    public final class LibreMapImpl implements IMap {
        private final MapView mapView;
        private final MapLibreMap map;
        private final Context context;
        private final LibreUISettings uiSettings;
        private final LibreProjection projection;

        private boolean dark;
        private Style style;
        private final ArrayList<Runnable> whenStyleReady = new ArrayList<>();
        private Runnable mapLoadedCallback;
        private boolean mapLoadedDelivered;

        private final ArrayList<LibreMarker> markers = new ArrayList<>();
        private final HashMap<String, Bitmap> icons = new HashMap<>();
        private final ArrayList<LibreCircle> circles = new ArrayList<>();
        private OnMarkerClickListener markerClickListener;

        private boolean myLocationEnabled;
        private Consumer<Location> myLocationListener;
        private LocationManager locationManager;
        private LocationListener locationListener;

        private LibreMapImpl(MapView mapView, MapLibreMap map) {
            this.mapView = mapView;
            this.map = map;
            this.context = mapView.getContext();
            this.uiSettings = new LibreUISettings(map.getUiSettings());
            this.projection = new LibreProjection(map);
            map.setMinZoomPreference(MIN_ZOOM);
            UiSettings ui = map.getUiSettings();
            ui.setCompassEnabled(false);
            ui.setLogoEnabled(false);
            ui.setRotateGesturesEnabled(false);
            ui.setTiltGesturesEnabled(false);
            ui.setAttributionEnabled(true);
            ui.setAttributionGravity(Gravity.BOTTOM | Gravity.LEFT);
            ui.setAttributionMargins(AndroidUtilities.dp(8), 0, 0, AndroidUtilities.dp(8));
            map.addOnMapClickListener(latLng -> {
                if (markerClickListener == null || style == null) {
                    return false;
                }
                PointF point = map.getProjection().toScreenLocation(latLng);
                List<Feature> hits = map.queryRenderedFeatures(point, MARKERS_LAYER);
                for (Feature hit : hits) {
                    LibreMarker marker = findMarker(hit.getStringProperty("id"));
                    if (marker != null) {
                        return markerClickListener.onClick(marker);
                    }
                }
                return false;
            });
            loadStyle();
        }

        private void loadStyle() {
            style = null;
            map.setStyle(new Style.Builder().fromUri(dark ? STYLE_DARK : STYLE_LIGHT), loaded -> {
                style = loaded;
                installLayers();
                for (Bitmap bitmap : icons.values()) {
                    loaded.addImage(iconName(bitmap), bitmap);
                }
                pushMarkers();
                pushCircles();
                if (myLocationEnabled) {
                    enableLocationComponent();
                }
                ArrayList<Runnable> pending = new ArrayList<>(whenStyleReady);
                whenStyleReady.clear();
                for (Runnable r : pending) {
                    r.run();
                }
                if (mapLoadedCallback != null && !mapLoadedDelivered) {
                    mapLoadedDelivered = true;
                    mapLoadedCallback.run();
                }
            });
        }

        private void installLayers() {
            style.addSource(new GeoJsonSource(CIRCLES_SOURCE, FeatureCollection.fromFeatures(new ArrayList<>())));
            style.addLayer(new FillLayer(CIRCLES_FILL_LAYER, CIRCLES_SOURCE).withProperties(
                PropertyFactory.fillColor(Expression.toColor(Expression.get("fill")))
            ));
            style.addLayer(new LineLayer(CIRCLES_LINE_LAYER, CIRCLES_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
                PropertyFactory.lineWidth(Expression.toNumber(Expression.get("width")))
            ).withFilter(Expression.eq(Expression.get("dashed"), Expression.literal(false))));
            style.addLayer(new LineLayer(CIRCLES_DASHED_LAYER, CIRCLES_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get("stroke"))),
                PropertyFactory.lineWidth(Expression.toNumber(Expression.get("width"))),
                PropertyFactory.lineDasharray(new Float[]{2f, 2f})
            ).withFilter(Expression.eq(Expression.get("dashed"), Expression.literal(true))));
            style.addSource(new GeoJsonSource(MARKERS_SOURCE, FeatureCollection.fromFeatures(new ArrayList<>())));
            style.addLayer(new SymbolLayer(MARKERS_LAYER, MARKERS_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconAnchor("center"),
                PropertyFactory.iconOffset(Expression.get("offset")),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("rotate"))),
                PropertyFactory.iconRotationAlignment("viewport"),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            ));
        }

        private void whenStyleReady(Runnable r) {
            if (style != null) {
                r.run();
            } else {
                whenStyleReady.add(r);
            }
        }

        private void destroy() {
            stopLocationUpdates();
        }

        /* markers */

        private String iconName(Bitmap bitmap) {
            return "tg-icon-" + System.identityHashCode(bitmap);
        }

        private String registerIcon(Bitmap bitmap) {
            String name = iconName(bitmap);
            if (!icons.containsKey(name)) {
                icons.put(name, bitmap);
                if (style != null) {
                    style.addImage(name, bitmap);
                }
            }
            return name;
        }

        private LibreMarker findMarker(String id) {
            for (LibreMarker marker : markers) {
                if (marker.id.equals(id)) {
                    return marker;
                }
            }
            return null;
        }

        private void pushMarkers() {
            if (style == null) {
                return;
            }
            GeoJsonSource source = style.getSourceAs(MARKERS_SOURCE);
            if (source == null) {
                return;
            }
            ArrayList<Feature> features = new ArrayList<>(markers.size());
            for (LibreMarker marker : markers) {
                features.add(marker.toFeature());
            }
            source.setGeoJson(FeatureCollection.fromFeatures(features));
        }

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            LibreMarkerOptions options = (LibreMarkerOptions) markerOptions;
            LibreMarker marker = new LibreMarker();
            marker.position = options.position != null ? options.position : new LatLng(0, 0);
            marker.anchorU = options.anchorU;
            marker.anchorV = options.anchorV;
            Bitmap bitmap = options.icon;
            if (bitmap == null && options.iconResId != 0) {
                bitmap = BitmapFactory.decodeResource(context.getResources(), options.iconResId);
            }
            if (bitmap != null) {
                marker.setIconBitmap(bitmap);
            }
            markers.add(marker);
            pushMarkers();
            return marker;
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener markerClickListener) {
            this.markerClickListener = markerClickListener;
        }

        public final class LibreMarker implements IMarker {
            private final String id = "m" + System.nanoTime();
            private Object tag;
            private LatLng position;
            private float anchorU = 0.5f, anchorV = 1f;
            private float rotation;
            private String icon;
            private int iconWidth, iconHeight;

            private void setIconBitmap(Bitmap bitmap) {
                icon = registerIcon(bitmap);
                iconWidth = bitmap.getWidth();
                iconHeight = bitmap.getHeight();
            }

            private Feature toFeature() {
                JsonObject props = new JsonObject();
                props.addProperty("id", id);
                props.addProperty("icon", icon == null ? "" : icon);
                props.addProperty("rotate", rotation);
                JsonArray offset = new JsonArray();
                float density = AndroidUtilities.density <= 0 ? 1 : AndroidUtilities.density;
                offset.add((0.5f - anchorU) * iconWidth / density);
                offset.add((0.5f - anchorV) * iconHeight / density);
                props.add("offset", offset);
                return Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(position.longitude, position.latitude), props, id);
            }

            @Override
            public Object getTag() {
                return tag;
            }

            @Override
            public void setTag(Object tag) {
                this.tag = tag;
            }

            @Override
            public LatLng getPosition() {
                return position;
            }

            @Override
            public void setPosition(LatLng latLng) {
                position = latLng;
                pushMarkers();
            }

            @Override
            public void setRotation(int rotation) {
                this.rotation = rotation;
                pushMarkers();
            }

            @Override
            public void setIcon(Bitmap bitmap) {
                setIconBitmap(bitmap);
                pushMarkers();
            }

            @Override
            public void setIcon(int resId) {
                Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), resId);
                if (bitmap != null) {
                    setIconBitmap(bitmap);
                    pushMarkers();
                }
            }

            @Override
            public void remove() {
                markers.remove(this);
                pushMarkers();
            }
        }

        /* circles */

        private void pushCircles() {
            if (style == null) {
                return;
            }
            GeoJsonSource source = style.getSourceAs(CIRCLES_SOURCE);
            if (source == null) {
                return;
            }
            ArrayList<Feature> features = new ArrayList<>(circles.size());
            for (LibreCircle circle : circles) {
                if (circle.center != null) {
                    features.add(circle.toFeature());
                }
            }
            source.setGeoJson(FeatureCollection.fromFeatures(features));
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            LibreCircleOptions options = (LibreCircleOptions) circleOptions;
            LibreCircle circle = new LibreCircle();
            circle.center = options.center;
            circle.radius = options.radius;
            circle.strokeColor = options.strokeColor;
            circle.fillColor = options.fillColor;
            circle.strokeWidth = options.strokeWidth;
            circle.dashed = options.dashed;
            circles.add(circle);
            pushCircles();
            return circle;
        }

        public final class LibreCircle implements ICircle {
            private LatLng center;
            private double radius;
            private int strokeColor;
            private int fillColor;
            private int strokeWidth;
            private boolean dashed;

            private Feature toFeature() {
                List<org.maplibre.geojson.Point> ring = new ArrayList<>(65);
                double latRad = Math.toRadians(center.latitude);
                double dLat = radius / 111320.0;
                double dLng = radius / (111320.0 * Math.max(0.01, Math.cos(latRad)));
                for (int i = 0; i <= 64; i++) {
                    double a = 2 * Math.PI * i / 64;
                    ring.add(org.maplibre.geojson.Point.fromLngLat(center.longitude + dLng * Math.cos(a), center.latitude + dLat * Math.sin(a)));
                }
                List<List<org.maplibre.geojson.Point>> rings = new ArrayList<>();
                rings.add(ring);
                JsonObject props = new JsonObject();
                props.addProperty("fill", rgba(fillColor));
                props.addProperty("stroke", rgba(strokeColor));
                props.addProperty("width", Math.max(1f, strokeWidth / (AndroidUtilities.density <= 0 ? 1 : AndroidUtilities.density)));
                props.addProperty("dashed", dashed);
                return Feature.fromGeometry(Polygon.fromLngLats(rings), props);
            }

            @Override
            public void setStrokeColor(int color) {
                strokeColor = color;
                pushCircles();
            }

            @Override
            public void setFillColor(int color) {
                fillColor = color;
                pushCircles();
            }

            @Override
            public void setRadius(double radius) {
                this.radius = radius;
                pushCircles();
            }

            @Override
            public double getRadius() {
                return radius;
            }

            @Override
            public void setCenter(LatLng latLng) {
                center = latLng;
                pushCircles();
            }

            @Override
            public void remove() {
                circles.remove(this);
                pushCircles();
            }
        }

        /* camera */

        @Override
        public void setMapType(int mapType) {
            // one vector layer
        }

        @Override
        public void animateCamera(ICameraUpdate update) {
            map.animateCamera(((LibreCameraUpdate) update).update, DEFAULT_ANIMATION_MS);
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            animateCamera(update, DEFAULT_ANIMATION_MS, callback);
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            map.animateCamera(((LibreCameraUpdate) update).update, Math.max(1, duration), callback == null ? null : new MapLibreMap.CancelableCallback() {
                @Override
                public void onCancel() {
                    callback.onCancel();
                }

                @Override
                public void onFinish() {
                    callback.onFinish();
                }
            });
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            map.moveCamera(((LibreCameraUpdate) update).update);
        }

        @Override
        public float getMaxZoomLevel() {
            return (float) map.getMaxZoomLevel();
        }

        @Override
        public float getMinZoomLevel() {
            return (float) map.getMinZoomLevel();
        }

        @Override
        public IUISettings getUiSettings() {
            return uiSettings;
        }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            map.addOnCameraIdleListener(callback::run);
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener listener) {
            map.addOnCameraMoveStartedListener(reason -> {
                int outReason;
                switch (reason) {
                    case MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE:
                        outReason = OnCameraMoveStartedListener.REASON_GESTURE;
                        break;
                    case MapLibreMap.OnCameraMoveStartedListener.REASON_API_ANIMATION:
                        outReason = OnCameraMoveStartedListener.REASON_API_ANIMATION;
                        break;
                    default:
                        outReason = OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION;
                        break;
                }
                listener.onCameraMoveStarted(outReason);
            });
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            map.addOnCameraMoveListener(callback::run);
        }

        @Override
        public CameraPosition getCameraPosition() {
            org.maplibre.android.camera.CameraPosition position = map.getCameraPosition();
            return new CameraPosition(fromLatLng(position.target), (float) position.zoom);
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            mapLoadedCallback = callback;
            if (style != null && !mapLoadedDelivered) {
                mapLoadedDelivered = true;
                AndroidUtilities.runOnUIThread(callback);
            }
        }

        @Override
        public IProjection getProjection() {
            return projection;
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            map.setPadding(left, top, right, bottom);
        }

        @Override
        public void setMapStyle(IMapStyleOptions styleOptions) {
            boolean wantDark = styleOptions != null && ((LibreMapStyleOptions) styleOptions).dark;
            if (wantDark != dark) {
                dark = wantDark;
                loadStyle();
            }
        }

        /* my location */

        @Override
        public void setMyLocationEnabled(boolean enabled) {
            myLocationEnabled = enabled;
            if (enabled) {
                startLocationUpdates();
                whenStyleReady(this::enableLocationComponent);
            } else {
                stopLocationUpdates();
                try {
                    LocationComponent component = map.getLocationComponent();
                    if (component.isLocationComponentActivated()) {
                        component.setLocationComponentEnabled(false);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }

        @SuppressLint("MissingPermission")
        private void enableLocationComponent() {
            if (style == null || !myLocationEnabled) {
                return;
            }
            try {
                LocationComponent component = map.getLocationComponent();
                if (!component.isLocationComponentActivated()) {
                    LocationComponentOptions options = LocationComponentOptions.builder(context)
                        .pulseEnabled(false)
                        .build();
                    component.activateLocationComponent(LocationComponentActivationOptions.builder(context, style)
                        .locationComponentOptions(options)
                        .useDefaultLocationEngine(false)
                        .build());
                }
                component.setLocationComponentEnabled(true);
                component.setCameraMode(CameraMode.NONE);
                component.setRenderMode(RenderMode.NORMAL);
                Location last = lastKnownLocation();
                if (last != null) {
                    component.forceLocationUpdate(last);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        @SuppressLint("MissingPermission")
        private Location lastKnownLocation() {
            try {
                LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                Location gps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location net = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (gps != null && net != null) {
                    return gps.getTime() >= net.getTime() ? gps : net;
                }
                return gps != null ? gps : net;
            } catch (Throwable e) {
                return null;
            }
        }

        @SuppressLint("MissingPermission")
        private void startLocationUpdates() {
            if (locationListener != null) {
                return;
            }
            try {
                locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                locationListener = location -> AndroidUtilities.runOnUIThread(() -> onLocation(location));
                for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                    try {
                        if (locationManager.isProviderEnabled(provider)) {
                            locationManager.requestLocationUpdates(provider, 1000, 1, locationListener);
                        }
                    } catch (Throwable ignore) {
                    }
                }
                Location last = lastKnownLocation();
                if (last != null) {
                    onLocation(last);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        private void stopLocationUpdates() {
            if (locationManager != null && locationListener != null) {
                try {
                    locationManager.removeUpdates(locationListener);
                } catch (Throwable ignore) {
                }
            }
            locationListener = null;
        }

        private void onLocation(Location location) {
            if (location == null) {
                return;
            }
            try {
                LocationComponent component = map.getLocationComponent();
                if (component.isLocationComponentActivated()) {
                    component.forceLocationUpdate(location);
                }
            } catch (Throwable ignore) {
            }
            if (myLocationListener != null) {
                myLocationListener.accept(location);
            }
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            myLocationListener = callback;
        }
    }

    /* small types */

    public static final class LibreProjection implements IProjection {
        private final MapLibreMap map;

        private LibreProjection(MapLibreMap map) {
            this.map = map;
        }

        @Override
        public Point toScreenLocation(LatLng latLng) {
            PointF point = map.getProjection().toScreenLocation(toLatLng(latLng));
            return new Point(Math.round(point.x), Math.round(point.y));
        }
    }

    public static final class LibreUISettings implements IUISettings {
        private final UiSettings uiSettings;

        private LibreUISettings(UiSettings uiSettings) {
            this.uiSettings = uiSettings;
        }

        @Override
        public void setZoomControlsEnabled(boolean enabled) {
        }

        @Override
        public void setMyLocationButtonEnabled(boolean enabled) {
        }

        @Override
        public void setCompassEnabled(boolean enabled) {
            uiSettings.setCompassEnabled(enabled);
        }
    }

    public static final class LibreMarkerOptions implements IMarkerOptions {
        private LatLng position;
        private Bitmap icon;
        private int iconResId;
        private float anchorU = 0.5f, anchorV = 1f;

        @Override
        public IMarkerOptions position(LatLng latLng) {
            position = latLng;
            return this;
        }

        @Override
        public IMarkerOptions icon(Bitmap bitmap) {
            icon = bitmap;
            return this;
        }

        @Override
        public IMarkerOptions icon(int resId) {
            iconResId = resId;
            return this;
        }

        @Override
        public IMarkerOptions anchor(float lat, float lng) {
            anchorU = lat;
            anchorV = lng;
            return this;
        }

        @Override
        public IMarkerOptions title(String title) {
            return this;
        }

        @Override
        public IMarkerOptions snippet(String snippet) {
            return this;
        }

        @Override
        public IMarkerOptions flat(boolean flat) {
            return this;
        }
    }

    public static final class LibreCircleOptions implements ICircleOptions {
        private LatLng center;
        private double radius;
        private int strokeColor = 0xff000000;
        private int fillColor = 0;
        private int strokeWidth = 2;
        private boolean dashed;

        @Override
        public ICircleOptions center(LatLng latLng) {
            center = latLng;
            return this;
        }

        @Override
        public ICircleOptions radius(double radius) {
            this.radius = radius;
            return this;
        }

        @Override
        public ICircleOptions strokeColor(int color) {
            strokeColor = color;
            return this;
        }

        @Override
        public ICircleOptions fillColor(int color) {
            fillColor = color;
            return this;
        }

        @Override
        public ICircleOptions strokePattern(List<PatternItem> patternItems) {
            dashed = false;
            if (patternItems != null) {
                for (PatternItem item : patternItems) {
                    if (item instanceof PatternItem.Gap) {
                        dashed = true;
                    }
                }
            }
            return this;
        }

        @Override
        public ICircleOptions strokeWidth(int width) {
            strokeWidth = width;
            return this;
        }
    }

    public static final class LibreLatLngBoundsBuilder implements ILatLngBoundsBuilder {
        private final LatLngBounds.Builder builder = new LatLngBounds.Builder();

        @Override
        public ILatLngBoundsBuilder include(LatLng latLng) {
            builder.include(toLatLng(latLng));
            return this;
        }

        @Override
        public ILatLngBounds build() {
            return new LibreLatLngBounds(builder.build());
        }
    }

    public static final class LibreLatLngBounds implements ILatLngBounds {
        private final LatLngBounds bounds;

        private LibreLatLngBounds(LatLngBounds bounds) {
            this.bounds = bounds;
        }

        @Override
        public LatLng getCenter() {
            return fromLatLng(bounds.getCenter());
        }
    }

    public static final class LibreCameraUpdate implements ICameraUpdate {
        private final CameraUpdate update;

        private LibreCameraUpdate(CameraUpdate update) {
            this.update = update;
        }
    }

    public static final class LibreMapStyleOptions implements IMapStyleOptions {
        private final boolean dark;

        private LibreMapStyleOptions(boolean dark) {
            this.dark = dark;
        }
    }
}
