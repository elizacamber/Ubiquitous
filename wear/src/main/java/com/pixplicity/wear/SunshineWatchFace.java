/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pixplicity.wear;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallbacks;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String TAG = SunshineWatchFace.class.getSimpleName();

    private static final String WEARABLE_DATA_PATH = "/wearable_data";
    private static final String KEY_LOW_TEMP = "key_low_temp";
    private static final String KEY_HIGH_TEMP = "key_high_temp";
    private static final String KEY_ASSET = "key_asset";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimeTextPaint;
        Paint mDateTextPaint;
        Paint mDividerPaint;
        Paint mHighTempTextPaint;
        Paint mLowTempTextPaint;
        Paint mIconPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mYOffsetDivider;
        float mYOffsetTime;
        float mYOffsetDate;
        float mYOffsetTemp;

        double mHighTemp;
        double mLowTemp;
        Bitmap mIcon;

        GoogleApiClient mGoogleApiClient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private SimpleDateFormat mDayOfWeekFormat;
        private Date mDate;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();

            Resources resources = SunshineWatchFace.this.getResources();

            mYOffsetDate = getResources().getDimension(R.dimen.digital_y_offset_date);
            mYOffsetTime = getResources().getDimension(R.dimen.digital_y_offset_time);
            mYOffsetTemp = getResources().getDimension(R.dimen.digital_y_offset_temp);
            mYOffsetDivider = getResources().getDimension(R.dimen.digital_y_offset_divider);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(SunshineWatchFace.this, R.color.primary));

            mDividerPaint = new Paint();
            mDividerPaint.setColor(ContextCompat.getColor(SunshineWatchFace.this, R.color.digital_text_opacity));

            mTimeTextPaint = new Paint();
            mTimeTextPaint = createBoldTextPaint(ContextCompat.getColor(SunshineWatchFace.this, R.color.digital_text));

            mDateTextPaint = new Paint();
            mDateTextPaint = createNormalTextPaint(ContextCompat.getColor(SunshineWatchFace.this, R.color.digital_text_opacity));

            mHighTempTextPaint = new Paint();
            mHighTempTextPaint = createNormalTextPaint(ContextCompat.getColor(SunshineWatchFace.this, R.color.digital_text));

            mLowTempTextPaint = new Paint();
            mLowTempTextPaint = createNormalTextPaint(ContextCompat.getColor(SunshineWatchFace.this, R.color.digital_text_opacity));

            mIconPaint = new Paint();

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initDateFormat();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createBoldTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(BOLD_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private Paint createNormalTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private void initDateFormat() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
        }

        public String formatTemperature(double temperature) {
            // TODO: Take user's preference under consideration
//            if (!isMetric(context)) {
//                temperature = (temperature * 1.8) + 32;
//            }

            return String.format(getString(R.string.format_temperature), temperature);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                mGoogleApiClient.connect();
                invalidate();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float timeTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_date_size_round : R.dimen.digital_date_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_size_round : R.dimen.digital_temp_size);

            mTimeTextPaint.setTextSize(timeTextSize);
            mDateTextPaint.setTextSize(dateTextSize);
            mHighTempTextPaint.setTextSize(tempTextSize);
            mLowTempTextPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimeTextPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!inAmbientMode);
                    mLowTempTextPaint.setAntiAlias(!inAmbientMode);
                    mHighTempTextPaint.setAntiAlias(!inAmbientMode);
                    //TODO: Do something also for the icon
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            // String timeText = String.format("%d:%02d", mCalendar.get(Calendar.HOUR), mCalendar.get(Calendar.MINUTE));
            String timeText = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(mDate);

            /** Center the digital time **/
            Rect textBounds = new Rect();
            mTimeTextPaint.getTextBounds(timeText, 0, timeText.length(), textBounds);
            float xTime = bounds.width() / 2 - textBounds.width() / 2;
            canvas.drawText(timeText, xTime, mYOffsetTime, mTimeTextPaint);

            /** Show date and weather only if there's no peek card **/
            if (getPeekCardPosition().isEmpty()) {
                String dateToday = mDayOfWeekFormat.format(mDate).replace(".", "").toUpperCase();
                float xOffsetDate = (bounds.width() - mDateTextPaint.measureText(dateToday)) / 2;
                // Day of week
                canvas.drawText(dateToday, xOffsetDate, mYOffsetDate, mDateTextPaint);

                int dividerWidth = (int) getResources().getDimension(R.dimen.width_divider_line);
                float xOffsetDivider = (bounds.width() - dividerWidth) / 2;
                canvas.drawLine(xOffsetDivider, mYOffsetDivider, xOffsetDivider + dividerWidth,
                        mYOffsetDivider, mDividerPaint);

                if (mLowTemp != 0 && mHighTemp != 0 && mIcon != null) {
                    float scale = (mHighTempTextPaint.getTextSize() / mIcon.getHeight()) * mIcon.getWidth();
                    mIcon = Bitmap.createScaledBitmap(mIcon, (int) scale + 45, (int) mHighTempTextPaint.getTextSize() + 45, true);

                    float xOffsetIcon = canvas.getWidth() / 2 - mIcon.getWidth() - 45;
                    float xOffsetHighTemp = xOffsetIcon + mIcon.getWidth() + 10;
                    float xOffsetLowTemp = xOffsetHighTemp + 75;

                    canvas.drawBitmap(mIcon, xOffsetIcon, mYOffsetTemp + mHighTempTextPaint.getTextSize() / 2 - mIcon.getHeight(), mIconPaint);
                    canvas.drawText(formatTemperature(mHighTemp), xOffsetHighTemp, mYOffsetTemp, mHighTempTextPaint);
                    canvas.drawText(formatTemperature(mLowTemp), xOffsetLowTemp, mYOffsetTemp, mLowTempTextPaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.v(TAG, "GoogleApiClient connected");
            Wearable.DataApi.addListener(mGoogleApiClient, this);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.v(TAG, "GoogleApiClient connection suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.v(TAG, "GoogleApiClient failed to connect" + connectionResult);
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.v(TAG, "DataEventBuffer: " + dataEventBuffer);

            for (DataEvent event : dataEventBuffer) {
                DataItem item = event.getDataItem();
                if (WEARABLE_DATA_PATH.equals(item.getUri().getPath())) {
                    DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    mHighTemp = dataMap.getDouble(KEY_HIGH_TEMP);
                    mLowTemp = dataMap.getDouble(KEY_LOW_TEMP);
                    Asset asset = dataMap.getAsset(KEY_ASSET);
                    loadBitmapFromAsset(asset);
                }
            }
        }

        private void loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }

            Wearable.DataApi.getFdForAsset(mGoogleApiClient, asset).setResultCallback(new ResultCallbacks<DataApi.GetFdForAssetResult>() {
                @Override
                public void onSuccess(@NonNull DataApi.GetFdForAssetResult getFdForAssetResult) {
                    InputStream assetInputStream = getFdForAssetResult.getInputStream();
                    mIcon = BitmapFactory.decodeStream(assetInputStream);
                }

                @Override
                public void onFailure(@NonNull Status status) {
                    mIcon = null;
                    Log.e(TAG, "loadBitmapFromAsset : failure");
                }
            });
        }
    }
}
