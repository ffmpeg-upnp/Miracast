
package com.milink.bonjour;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.milink.bonjour.serviceinfo.BonjourServiceInfo;
import com.milink.net.util.NetWork;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

public class Bonjour implements Runnable, ServiceListener {

    private static final String TAG = Bonjour.class.getSimpleName();
//    public static final String AIRPLAY = "_airplay._tcp.local.";
//    public static final String AIRTUNES = "_roap._tcp.local.";
//    public static final String DACP = "_dacp._tcp.local.";

    private WifiManager.MulticastLock mWifiLock = null;
    private static byte[] mJmdnsLock = new byte[0];
    private JmDNS mJmdns = null;
    private Thread mThread = null;
    private BonjourListener mListener = null;
    private Context mContext = null;
    private boolean mStarted = false;
    private Map<String, ServiceInfo> mSvcInfoList = new HashMap<String, ServiceInfo>();
    private ArrayList<String> mSvcType = new ArrayList<String>();
    
    public Bonjour(Context context, BonjourListener listener) {
        mContext = context;
        mListener = listener;
    }

    public boolean isStarted() {
        return mStarted;
    }

    public void start() {
        if (mStarted)
            return;

        Log.v(TAG, "start");

        synchronized (mJmdnsLock) {
            if (mJmdns == null) {
                mThread = new Thread(this);
                mThread.start();
            }
        }
    }

    public void stop() {
        if (!mStarted)
            return;

        Log.v(TAG, "stop");

        if (mJmdns != null) {
            try {
                mJmdns.unregisterAllServices();
                mSvcInfoList.clear();

                mJmdns.close();
                mJmdns = null;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (mWifiLock != null) {
            mWifiLock.setReferenceCounted(false);
            mWifiLock.release();
        }
    }

    public void publishService(BonjourServiceInfo svcInfo) {
        synchronized (mJmdnsLock) {
            ServiceInfo serviceInfo = ServiceInfo.create(svcInfo.getServiceType(),
                    svcInfo.getServiceName(),
                    svcInfo.getServicePort(),
                    0,
                    0,
                    svcInfo.getProperties());

            mSvcInfoList.put(svcInfo.getServiceName(), serviceInfo);

            try {
                mJmdns.registerService(serviceInfo);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void discoveryService(String serviceType) {
        synchronized (mJmdnsLock) {
            mSvcType.add(serviceType);
            
            if (mStarted) {
                Log.d(TAG, String.format("discoveryService: %s", serviceType));
//                mJmdns.addServiceListener(serviceType, this);
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        this.stop();
        super.finalize();
    }

    @Override
    public void run() {
        synchronized (mJmdnsLock) {
            WifiManager wifi = (WifiManager) mContext
                    .getSystemService(android.content.Context.WIFI_SERVICE);

            mWifiLock = wifi.createMulticastLock("bonjourlock");
            mWifiLock.setReferenceCounted(true);
            mWifiLock.acquire();

            try {
                byte[] ip = NetWork.getLocalIpInt(mContext);
                if (ip == null)
                    return;

                InetAddress addr = InetAddress.getByAddress(ip);

                mJmdns = JmDNS.create(addr);
                Log.d(TAG, String.format("JmDNS version: %s %s", JmDNS.VERSION,
                        addr.toString()));

                for (String t : mSvcType) {
                    Log.d(TAG, String.format("addServiceListener: %s", t));
                    mJmdns.addServiceListener(t, this);
                }

                mStarted = true;
            } catch (IOException e) {
                Log.e(TAG, "JmDNS.create() failed!");
                e.printStackTrace();
            }
        }
    }

    @Override
    public void serviceAdded(ServiceEvent event) {
        Log.d(TAG,
                String.format("serviceAdded: %s.%s", event.getName(),
                        event.getType()));
        mJmdns.requestServiceInfo(event.getType(), event.getName());
    }

    @Override
    public void serviceRemoved(ServiceEvent event) {
        String name = event.getName();
        String type = event.getType();
        String ip = null;

        Inet4Address[] addresses = event.getInfo().getInet4Addresses();
        for (int i = 0; i < addresses.length; ++i) {
            ip = addresses[i].getHostAddress();
        }

        Log.d(TAG, String.format("serviceRemoved: %s.%s %s", event.getName(),
                event.getType(), ip));
        mListener.onServiceLost(name, type, ip);
    }

    @Override
    public void serviceResolved(ServiceEvent event) {
        String name = event.getName();
        String type = event.getType();
        int port = event.getInfo().getPort();
        String ip = null;

        Inet4Address[] addresses = event.getInfo().getInet4Addresses();
        for (int i = 0; i < addresses.length; ++i) {
            ip = addresses[i].getHostAddress();

            Log.d(TAG,
                    String.format("serviceResolved: %s.%s %s:%d",
                            event.getName(), event.getType(), ip, port));
        }

        if (ip == null)
            return;

        Map<String, String> properties = new HashMap<String, String>();

        Enumeration<String> propertyNames = event.getInfo().getPropertyNames();
        while (propertyNames.hasMoreElements()) {
            String key = propertyNames.nextElement();
            String value = event.getInfo().getPropertyString(key);
            
            Log.d(TAG, String.format("%s=%s", key, value));

            properties.put(key, value);
        }

        mListener.onServiceFound(name, type, ip, port, properties);
    }
}