package org.telegram.messenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.util.Consumer;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.DelayedMapListener;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.PointL;
import org.osmdroid.util.TileSystem;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.CopyrightOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenStreetMap implementation of {@link IMapsProvider} on top of osmdroid, for builds that are
 * not signed with Telegram's certificate and therefore cannot use its Google Maps key.
 * <p>
 * Differences from the Google provider that the UI code can notice: there is no satellite
 * layer (map types are ignored), night mode is a colour transform of the standard tiles rather
 * than a styled map, and camera padding is emulated by shifting the target so the point of
 * interest still lands in the uncovered part of the view.
 */
public class OsmMapsProvider implements IMapsProvider {

    private static final double MIN_ZOOM = 2;
    private static final double MAX_ZOOM = 21; // tiles stop at 19; the UI's "max - 4" convention expects Google's 21
    private static final long DEFAULT_ANIMATION_MS = 400;

    @Override
    public void initializeMaps(Context context) {
        try {
            Configuration.getInstance().setUserAgentValue(context.getPackageName() + "/" + BuildVars.BUILD_VERSION_STRING);
            File cache = new File(context.getCacheDir(), "osmdroid");
            cache.mkdirs();
            Configuration.getInstance().setOsmdroidBasePath(cache);
            Configuration.getInstance().setOsmdroidTileCache(new File(cache, "tiles"));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public IMapView onCreateMapView(Context context) {
        initializeMaps(context);
        return new OsmMapView(context);
    }

    @Override
    public IMarkerOptions onCreateMarkerOptions() {
        return new OsmMarkerOptions();
    }

    @Override
    public ICircleOptions onCreateCircleOptions() {
        return new OsmCircleOptions();
    }

    @Override
    public ILatLngBoundsBuilder onCreateLatLngBoundsBuilder() {
        return new OsmLatLngBoundsBuilder();
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLng(LatLng latLng) {
        return new OsmCameraUpdate(latLng, null, null, 0);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngZoom(LatLng latLng, float zoom) {
        return new OsmCameraUpdate(latLng, (double) zoom, null, 0);
    }

    @Override
    public ICameraUpdate newCameraUpdateLatLngBounds(ILatLngBounds bounds, int padding) {
        return new OsmCameraUpdate(null, null, ((OsmLatLngBounds) bounds).bounds, padding);
    }

    @Override
    public IMapStyleOptions loadRawResourceStyle(Context context, int resId) {
        return new OsmMapStyleOptions(resId == R.raw.mapstyle_night);
    }

    @Override
    public String getMapsAppPackageName() {
        return "com.google.android.apps.maps";
    }

    @Override
    public int getInstallMapsString() {
        return R.string.InstallGoogleMaps;
    }

    private static GeoPoint toGeo(LatLng latLng) {
        return new GeoPoint(latLng.latitude, latLng.longitude);
    }

    private static LatLng toLatLng(IGeoPoint point) {
        return new LatLng(point.getLatitude(), point.getLongitude());
    }

    /* map view */

    public final class OsmMapView implements IMapView {
        private final TouchAwareMapView mapView;
        private ITouchInterceptor dispatchInterceptor;
        private ITouchInterceptor interceptInterceptor;
        private Runnable onLayoutListener;
        private OsmMapImpl map;

        private final class TouchAwareMapView extends MapView {
            boolean userTouching;

            TouchAwareMapView(Context context) {
                super(context);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        userTouching = true;
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        userTouching = false;
                        break;
                }
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
        }

        private OsmMapView(Context context) {
            mapView = new TouchAwareMapView(context);
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            mapView.setMultiTouchControls(true);
            mapView.setTilesScaledToDpi(true);
            mapView.getZoomController().setVisibility(CustomZoomButtonsController.Visibility.NEVER);
            mapView.setMinZoomLevel(MIN_ZOOM);
            mapView.setMaxZoomLevel(MAX_ZOOM);
            mapView.setHorizontalMapRepetitionEnabled(true);
            mapView.setVerticalMapRepetitionEnabled(false);
            mapView.getController().setZoom(MIN_ZOOM);
            mapView.getOverlays().add(new CopyrightOverlay(context));
        }

        @Override
        public View getView() {
            return mapView;
        }

        @Override
        public void getMapAsync(Consumer<IMap> callback) {
            if (map == null) {
                map = new OsmMapImpl(mapView);
            }
            AndroidUtilities.runOnUIThread(() -> callback.accept(map));
        }

        @Override
        public void onResume() {
            mapView.onResume();
        }

        @Override
        public void onPause() {
            mapView.onPause();
        }

        @Override
        public void onCreate(Bundle savedInstance) {
        }

        @Override
        public void onDestroy() {
            if (map != null) {
                map.destroy();
            }
            mapView.onDetach();
        }

        @Override
        public void onLowMemory() {
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

    public final class OsmMapImpl implements IMap {
        private final OsmMapView.TouchAwareMapView mapView;
        private final ArrayList<OsmMarker> markers = new ArrayList<>();
        private OnMarkerClickListener markerClickListener;
        private Runnable cameraIdleListener;
        private Runnable cameraMoveListener;
        private OnCameraMoveStartedListener cameraMoveStartedListener;
        private boolean cameraMoving;
        private boolean programmaticMove;
        private int paddingLeft, paddingTop, paddingRight, paddingBottom;
        private MyLocationNewOverlay myLocationOverlay;
        private LocationForwardingProvider locationProvider;
        private Consumer<Location> myLocationListener;
        private final OsmUISettings uiSettings = new OsmUISettings();
        private final OsmProjection projection;

        private OsmMapImpl(OsmMapView.TouchAwareMapView mapView) {
            this.mapView = mapView;
            this.projection = new OsmProjection(mapView);
            MapListener listener = new MapListener() {
                @Override
                public boolean onScroll(ScrollEvent event) {
                    onCameraEvent();
                    return false;
                }

                @Override
                public boolean onZoom(ZoomEvent event) {
                    onCameraEvent();
                    return false;
                }
            };
            mapView.addMapListener(listener);
            mapView.addMapListener(new DelayedMapListener(new MapListener() {
                @Override
                public boolean onScroll(ScrollEvent event) {
                    onCameraIdle();
                    return false;
                }

                @Override
                public boolean onZoom(ZoomEvent event) {
                    onCameraIdle();
                    return false;
                }
            }, 120));
        }

        private void onCameraEvent() {
            if (!cameraMoving) {
                cameraMoving = true;
                if (cameraMoveStartedListener != null) {
                    int reason = programmaticMove ? OnCameraMoveStartedListener.REASON_DEVELOPER_ANIMATION
                        : mapView.userTouching ? OnCameraMoveStartedListener.REASON_GESTURE
                        : OnCameraMoveStartedListener.REASON_API_ANIMATION;
                    cameraMoveStartedListener.onCameraMoveStarted(reason);
                }
            }
            if (cameraMoveListener != null) {
                cameraMoveListener.run();
            }
        }

        private void onCameraIdle() {
            cameraMoving = false;
            programmaticMove = false;
            if (cameraIdleListener != null) {
                cameraIdleListener.run();
            }
        }

        private void destroy() {
            if (myLocationOverlay != null) {
                myLocationOverlay.disableMyLocation();
                myLocationOverlay = null;
            }
            if (locationProvider != null) {
                locationProvider.destroy();
                locationProvider = null;
            }
        }

        @Override
        public void setMapType(int mapType) {
            // OpenStreetMap has one layer
        }

        /** Where a point must land so that it is centred in the part of the view not covered by the padding. */
        private GeoPoint shiftForPadding(GeoPoint target, double zoom) {
            int dx = (paddingRight - paddingLeft) / 2;
            int dy = (paddingBottom - paddingTop) / 2;
            if (dx == 0 && dy == 0) {
                return target;
            }
            TileSystem tileSystem = MapView.getTileSystem();
            double mapSize = TileSystem.MapSize(zoom);
            PointL pixel = tileSystem.getMercatorFromGeo(target.getLatitude(), target.getLongitude(), mapSize, null, true);
            return tileSystem.getGeoFromMercator(pixel.x + dx, pixel.y + dy, mapSize, null, true, true);
        }

        private void applyCameraUpdate(OsmCameraUpdate update, boolean animated, long duration) {
            programmaticMove = true;
            if (update.bounds != null) {
                mapView.zoomToBoundingBox(update.bounds, animated, update.padding, MAX_ZOOM, animated ? duration : null);
                return;
            }
            double zoom = update.zoom != null ? update.zoom : mapView.getZoomLevelDouble();
            GeoPoint target = shiftForPadding(toGeo(update.latLng), zoom);
            if (animated) {
                mapView.getController().animateTo(target, zoom, duration);
            } else {
                mapView.getController().setZoom(zoom);
                mapView.getController().setCenter(target);
            }
        }

        @Override
        public void animateCamera(ICameraUpdate update) {
            animateCamera(update, null);
        }

        @Override
        public void animateCamera(ICameraUpdate update, ICancelableCallback callback) {
            animateCamera(update, (int) DEFAULT_ANIMATION_MS, callback);
        }

        @Override
        public void animateCamera(ICameraUpdate update, int duration, ICancelableCallback callback) {
            applyCameraUpdate((OsmCameraUpdate) update, true, duration);
            if (callback != null) {
                AndroidUtilities.runOnUIThread(callback::onFinish, duration + 50);
            }
        }

        @Override
        public void moveCamera(ICameraUpdate update) {
            applyCameraUpdate((OsmCameraUpdate) update, false, 0);
        }

        @Override
        public float getMaxZoomLevel() {
            return (float) MAX_ZOOM;
        }

        @Override
        public float getMinZoomLevel() {
            return (float) MIN_ZOOM;
        }

        @Override
        public void setMyLocationEnabled(boolean enabled) {
            if (enabled) {
                if (myLocationOverlay == null) {
                    locationProvider = new LocationForwardingProvider(mapView.getContext());
                    myLocationOverlay = new MyLocationNewOverlay(locationProvider, mapView);
                    Bitmap dot = createLocationDot(mapView.getContext());
                    myLocationOverlay.setPersonIcon(dot);
                    myLocationOverlay.setDirectionIcon(dot);
                    myLocationOverlay.setPersonAnchor(0.5f, 0.5f);
                    myLocationOverlay.setDirectionAnchor(0.5f, 0.5f);
                    myLocationOverlay.setDrawAccuracyEnabled(true);
                    mapView.getOverlays().add(0, myLocationOverlay);
                }
                myLocationOverlay.enableMyLocation();
            } else if (myLocationOverlay != null) {
                myLocationOverlay.disableMyLocation();
            }
            mapView.invalidate();
        }

        @Override
        public IUISettings getUiSettings() {
            return uiSettings;
        }

        @Override
        public void setOnCameraIdleListener(Runnable callback) {
            cameraIdleListener = callback;
        }

        @Override
        public void setOnCameraMoveStartedListener(OnCameraMoveStartedListener onCameraMoveStartedListener) {
            cameraMoveStartedListener = onCameraMoveStartedListener;
        }

        @Override
        public CameraPosition getCameraPosition() {
            return new CameraPosition(toLatLng(mapView.getMapCenter()), (float) mapView.getZoomLevelDouble());
        }

        @Override
        public void setOnMapLoadedCallback(Runnable callback) {
            if (mapView.getWidth() > 0 && mapView.getHeight() > 0) {
                AndroidUtilities.runOnUIThread(callback);
            } else {
                mapView.addOnFirstLayoutListener((v, left, top, right, bottom) -> AndroidUtilities.runOnUIThread(callback));
            }
        }

        @Override
        public IProjection getProjection() {
            return projection;
        }

        @Override
        public void setPadding(int left, int top, int right, int bottom) {
            paddingLeft = left;
            paddingTop = top;
            paddingRight = right;
            paddingBottom = bottom;
        }

        @Override
        public void setMapStyle(IMapStyleOptions style) {
            boolean dark = style != null && ((OsmMapStyleOptions) style).dark;
            mapView.getOverlayManager().getTilesOverlay().setColorFilter(dark ? nightFilter() : null);
            mapView.invalidate();
        }

        @Override
        public IMarker addMarker(IMarkerOptions markerOptions) {
            OsmMarkerOptions options = (OsmMarkerOptions) markerOptions;
            Marker marker = new Marker(mapView);
            marker.setInfoWindow(null);
            if (options.position != null) {
                marker.setPosition(toGeo(options.position));
            }
            if (options.icon != null) {
                marker.setIcon(options.icon);
            } else if (options.iconResId != 0) {
                marker.setIcon(mapView.getContext().getResources().getDrawable(options.iconResId));
            }
            marker.setAnchor(options.anchorU, options.anchorV);
            marker.setFlat(options.flat);
            if (options.title != null) {
                marker.setTitle(options.title);
            }
            OsmMarker result = new OsmMarker(marker);
            marker.setOnMarkerClickListener((m, map) -> markerClickListener != null && markerClickListener.onClick(result));
            markers.add(result);
            mapView.getOverlays().add(marker);
            mapView.invalidate();
            return result;
        }

        @Override
        public void setOnMyLocationChangeListener(Consumer<Location> callback) {
            myLocationListener = callback;
        }

        @Override
        public void setOnMarkerClickListener(OnMarkerClickListener markerClickListener) {
            this.markerClickListener = markerClickListener;
        }

        @Override
        public void setOnCameraMoveListener(Runnable callback) {
            cameraMoveListener = callback;
        }

        @Override
        public ICircle addCircle(ICircleOptions circleOptions) {
            OsmCircleOptions options = (OsmCircleOptions) circleOptions;
            Polygon polygon = new Polygon(mapView);
            polygon.setInfoWindow(null);
            Paint outline = polygon.getOutlinePaint();
            outline.setColor(options.strokeColor);
            outline.setStrokeWidth(options.strokeWidth);
            if (options.dashPattern != null) {
                outline.setPathEffect(new DashPathEffect(options.dashPattern, 0));
            }
            polygon.getFillPaint().setColor(options.fillColor);
            OsmCircle circle = new OsmCircle(polygon, options.center, options.radius);
            circle.rebuild();
            mapView.getOverlays().add(0, polygon);
            mapView.invalidate();
            return circle;
        }

        private final class LocationForwardingProvider extends GpsMyLocationProvider {
            LocationForwardingProvider(Context context) {
                super(context);
            }

            @Override
            public void onLocationChanged(Location location) {
                super.onLocationChanged(location);
                if (myLocationListener != null && location != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (myLocationListener != null) {
                            myLocationListener.accept(location);
                        }
                    });
                }
            }
        }

        public final class OsmMarker implements IMarker {
            private final Marker marker;
            private Object tag;

            private OsmMarker(Marker marker) {
                this.marker = marker;
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
                return toLatLng(marker.getPosition());
            }

            @Override
            public void setPosition(LatLng latLng) {
                marker.setPosition(toGeo(latLng));
                mapView.invalidate();
            }

            @Override
            public void setRotation(int rotation) {
                marker.setRotation(rotation);
                mapView.invalidate();
            }

            @Override
            public void setIcon(Bitmap bitmap) {
                marker.setIcon(new BitmapDrawable(mapView.getResources(), bitmap));
                mapView.invalidate();
            }

            @Override
            public void setIcon(int resId) {
                marker.setIcon(mapView.getContext().getResources().getDrawable(resId));
                mapView.invalidate();
            }

            @Override
            public void remove() {
                mapView.getOverlays().remove(marker);
                markers.remove(this);
                mapView.invalidate();
            }
        }

        public final class OsmCircle implements ICircle {
            private final Polygon polygon;
            private LatLng center;
            private double radius;

            private OsmCircle(Polygon polygon, LatLng center, double radius) {
                this.polygon = polygon;
                this.center = center;
                this.radius = radius;
            }

            private void rebuild() {
                if (center != null) {
                    polygon.setPoints(Polygon.pointsAsCircle(toGeo(center), radius));
                }
                mapView.invalidate();
            }

            @Override
            public void setStrokeColor(int color) {
                polygon.getOutlinePaint().setColor(color);
                mapView.invalidate();
            }

            @Override
            public void setFillColor(int color) {
                polygon.getFillPaint().setColor(color);
                mapView.invalidate();
            }

            @Override
            public void setRadius(double radius) {
                this.radius = radius;
                rebuild();
            }

            @Override
            public double getRadius() {
                return radius;
            }

            @Override
            public void setCenter(LatLng latLng) {
                center = latLng;
                rebuild();
            }

            @Override
            public void remove() {
                mapView.getOverlays().remove(polygon);
                mapView.invalidate();
            }
        }
    }

    /* small types */

    public static final class OsmProjection implements IProjection {
        private final MapView mapView;

        private OsmProjection(MapView mapView) {
            this.mapView = mapView;
        }

        @Override
        public Point toScreenLocation(LatLng latLng) {
            return mapView.getProjection().toPixels(toGeo(latLng), null);
        }
    }

    public static final class OsmUISettings implements IUISettings {
        @Override
        public void setZoomControlsEnabled(boolean enabled) {
        }

        @Override
        public void setMyLocationButtonEnabled(boolean enabled) {
        }

        @Override
        public void setCompassEnabled(boolean enabled) {
        }
    }

    public static final class OsmMarkerOptions implements IMarkerOptions {
        private LatLng position;
        private Drawable icon;
        private int iconResId;
        private float anchorU = 0.5f, anchorV = 1f;
        private String title;
        private boolean flat;

        @Override
        public IMarkerOptions position(LatLng latLng) {
            position = latLng;
            return this;
        }

        @Override
        public IMarkerOptions icon(Bitmap bitmap) {
            icon = new BitmapDrawable(ApplicationLoader.applicationContext.getResources(), bitmap);
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
            this.title = title;
            return this;
        }

        @Override
        public IMarkerOptions snippet(String snippet) {
            return this;
        }

        @Override
        public IMarkerOptions flat(boolean flat) {
            this.flat = flat;
            return this;
        }
    }

    public static final class OsmCircleOptions implements ICircleOptions {
        private LatLng center;
        private double radius;
        private int strokeColor = 0xff000000;
        private int fillColor = 0;
        private int strokeWidth = 2;
        private float[] dashPattern;

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
            if (patternItems == null || patternItems.isEmpty()) {
                dashPattern = null;
                return this;
            }
            List<Float> intervals = new ArrayList<>();
            for (PatternItem item : patternItems) {
                if (item instanceof PatternItem.Dash) {
                    intervals.add((float) ((PatternItem.Dash) item).length);
                } else if (item instanceof PatternItem.Gap) {
                    intervals.add((float) ((PatternItem.Gap) item).length);
                }
            }
            if (intervals.size() % 2 == 1) {
                intervals.add(intervals.get(intervals.size() - 1));
            }
            dashPattern = new float[intervals.size()];
            for (int i = 0; i < intervals.size(); i++) {
                dashPattern[i] = intervals.get(i);
            }
            return this;
        }

        @Override
        public ICircleOptions strokeWidth(int width) {
            strokeWidth = width;
            return this;
        }
    }

    public static final class OsmLatLngBoundsBuilder implements ILatLngBoundsBuilder {
        private final ArrayList<GeoPoint> points = new ArrayList<>();

        @Override
        public ILatLngBoundsBuilder include(LatLng latLng) {
            points.add(toGeo(latLng));
            return this;
        }

        @Override
        public ILatLngBounds build() {
            return new OsmLatLngBounds(BoundingBox.fromGeoPointsSafe(points));
        }
    }

    public static final class OsmLatLngBounds implements ILatLngBounds {
        private final BoundingBox bounds;

        private OsmLatLngBounds(BoundingBox bounds) {
            this.bounds = bounds;
        }

        @Override
        public LatLng getCenter() {
            return toLatLng(bounds.getCenterWithDateLine());
        }
    }

    public static final class OsmCameraUpdate implements ICameraUpdate {
        private final LatLng latLng;
        private final Double zoom;
        private final BoundingBox bounds;
        private final int padding;

        private OsmCameraUpdate(LatLng latLng, Double zoom, BoundingBox bounds, int padding) {
            this.latLng = latLng;
            this.zoom = zoom;
            this.bounds = bounds;
            this.padding = padding;
        }
    }

    public static final class OsmMapStyleOptions implements IMapStyleOptions {
        private final boolean dark;

        private OsmMapStyleOptions(boolean dark) {
            this.dark = dark;
        }
    }

    /* drawing helpers */

    private static ColorMatrixColorFilter nightFilter;

    /** Inverted lightness with hues kept: water stays blue, parks stay green, the map goes dark. */
    private static ColorMatrixColorFilter nightFilter() {
        if (nightFilter == null) {
            ColorMatrix invert = new ColorMatrix(new float[]{
                -1, 0, 0, 0, 255,
                0, -1, 0, 0, 255,
                0, 0, -1, 0, 255,
                0, 0, 0, 1, 0
            });
            ColorMatrix hueFlip = new ColorMatrix(new float[]{
                -1f / 3, 2f / 3, 2f / 3, 0, 0,
                2f / 3, -1f / 3, 2f / 3, 0, 0,
                2f / 3, 2f / 3, -1f / 3, 0, 0,
                0, 0, 0, 1, 0
            });
            ColorMatrix desaturate = new ColorMatrix();
            desaturate.setSaturation(0.75f);
            ColorMatrix matrix = new ColorMatrix(invert);
            matrix.postConcat(hueFlip);
            matrix.postConcat(desaturate);
            nightFilter = new ColorMatrixColorFilter(matrix);
        }
        return nightFilter;
    }

    private static Bitmap createLocationDot(Context context) {
        int size = AndroidUtilities.dp(22);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0x40000000);
        canvas.drawCircle(size / 2f, size / 2f + AndroidUtilities.dp(1), size / 2f, paint);
        paint.setColor(0xffffffff);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - AndroidUtilities.dp(1), paint);
        paint.setColor(0xff4285f4);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - AndroidUtilities.dp(4), paint);
        return bitmap;
    }
}
