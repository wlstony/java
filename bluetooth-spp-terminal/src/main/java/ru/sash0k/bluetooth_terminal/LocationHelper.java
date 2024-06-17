package ru.sash0k.bluetooth_terminal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import java.util.List;


public class LocationHelper implements LocationListener {

    private static final String TAG = "spp_debug";
    private final Context context;
    private LocationManager locationManager;
    public  boolean locationUpdateFlag = false;

    public LocationHelper(Context context) {
        this.context = context;
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }
    public Location getLastKnownLocation() {
        Location result = null;

        List<String> providers = locationManager.getProviders(true);
        for (String provider : providers) {
            Location location = locationManager.getLastKnownLocation(provider);
            if (location != null) {
                // 选择最近的位置
                if (result == null || location.getTime() > result.getTime()) {
                    result = location;
                }
            }
        }
        return result;
//        // 获取位置提供者，通常可以是GPS、网络或其他
//        String provider = LocationManager.GPS_PROVIDER;
//
//        // 检查是否有更精确的位置提供者可用
//        if (!locationManager.isProviderEnabled(provider)) {
//            provider = LocationManager.NETWORK_PROVIDER;
//        }
//
//        // 获取最后已知位置
//        Location lastKnownLocation = locationManager.getLastKnownLocation(provider);
//
//        // 返回最后已知位置，如果没有则为null
//        return lastKnownLocation;
    }


    public void requestLocationUpdates() {
        // 创建一个Criteria对象，设置精度、能耗等要求
        Criteria criteria = new Criteria();
        criteria.setAccuracy(Criteria.ACCURACY_FINE); // 设置精度要求
        criteria.setPowerRequirement(Criteria.POWER_LOW); // 设置能耗要求

        // 获取最佳的位置提供者
        String provider = locationManager.getBestProvider(criteria, true);

        if (provider != null) {
            // 请求位置更新，这里设置为每5秒（5000毫秒）或移动10米时更新一次
            locationManager.requestLocationUpdates(provider, 5000, 10, this);
            Log.d(TAG, "Location updates requested from provider: " + provider);
        } else {
            Log.e(TAG, "No location provider found.");
        }
        locationUpdateFlag = true;
    }

    public void removeLocationUpdates() {
        if (locationManager != null) {
            locationManager.removeUpdates(this);
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        // 当位置发生变化时调用
        // 在这里处理位置更新
        Log.d(TAG, "Location changed: " + location.toString());
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // 提供商状态发生变化时调用
        Log.d(TAG, "Provider status changed: " + provider + ", status: " + status);
    }

    @Override
    public void onProviderEnabled(String provider) {
        // 提供商被启用时调用
        Log.d(TAG, "Provider enabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        // 提供商被禁用时调用
        Log.d(TAG, "Provider disabled: " + provider);
    }
}