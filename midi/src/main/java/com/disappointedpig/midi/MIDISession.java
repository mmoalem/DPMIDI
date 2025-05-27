package com.disappointedpig.midi;

import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import androidx.collection.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import com.disappointedpig.midi.events.MIDIAddressBookEvent;
import com.disappointedpig.midi.events.MIDIConnectionEndEvent;
import com.disappointedpig.midi.events.MIDIConnectionEstablishedEvent;
import com.disappointedpig.midi.events.MIDIReceivedEvent;
import com.disappointedpig.midi.events.MIDISessionNameRegisteredEvent;
import com.disappointedpig.midi.events.MIDISessionStartEvent;
import com.disappointedpig.midi.events.MIDISessionStopEvent;
import com.disappointedpig.midi.events.MIDIServiceDiscoveredEvent; // Import the new event
import com.disappointedpig.midi.events.MIDISyncronizationCompleteEvent;
import com.disappointedpig.midi.events.MIDISyncronizationStartEvent;
import com.disappointedpig.midi.internal_events.AddressBookReadyEvent;
import com.disappointedpig.midi.internal_events.ConnectionEstablishedEvent;
import com.disappointedpig.midi.internal_events.ConnectionFailedEvent;
import com.disappointedpig.midi.internal_events.ListeningEvent;
import com.disappointedpig.midi.internal_events.PacketEvent;
import com.disappointedpig.midi.internal_events.StreamConnectedEvent;
import com.disappointedpig.midi.internal_events.StreamDisconnectEvent;
import com.disappointedpig.midi.internal_events.SyncronizeStartedEvent;
import com.disappointedpig.midi.internal_events.SyncronizeStoppedEvent;
// WaspDB / Kryo specific imports removed
// import com.esotericsoftware.kryo.KryoException;
// import net.rehacktive.waspdb.WaspDb;
// import net.rehacktive.waspdb.WaspFactory;
// import net.rehacktive.waspdb.WaspHash;
// import net.rehacktive.waspdb.WaspListener;
// import net.rehacktive.waspdb.WaspObserver;
import android.content.SharedPreferences; // Added for SharedPreferences

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Random;

import static android.content.Context.WIFI_SERVICE;
import static com.disappointedpig.midi.MIDIConstants.RINFO_ADDR;
import static com.disappointedpig.midi.MIDIConstants.RINFO_FAIL;
import static com.disappointedpig.midi.MIDIConstants.RINFO_PORT;
import static com.disappointedpig.midi.MIDIConstants.RINFO_RECON;

public class MIDISession {

    private static MIDISession midiSessionInstance;
    private static String TAG = MIDISession.class.getSimpleName();
    private static String BONJOUR_TYPE = "_apple-midi._udp";
    private static String BONJOUR_SEPARATOR = ".";
    private static boolean DEBUG = true;

    // WaspDB members removed
    // private WaspDb db;
    // private WaspHash midiAddressBook;
    // private WaspObserver observer;

    private static final String MIDI_ADDRESS_BOOK_PREFS = "MidiAddressBookPrefs";


    private MIDISession() {
        this.rate = 10000;
        this.port = 5004;
        final Random rand = new Random(System.currentTimeMillis());
        this.ssrc = (int) Math.round(rand.nextFloat() * Math.pow(2, 8 * 4));
        this.startTime = (System.currentTimeMillis() / 1000L) * (long)this.rate ;
        this.startTimeHR =  System.nanoTime();
        this.registered_eb = false;
        this.published_bonjour = false;
        this.autoReconnect = false;
    }

    public static MIDISession getInstance() {
        if(midiSessionInstance == null) {
            midiSessionInstance = new MIDISession();
        }
        return midiSessionInstance;
    }

    private Boolean shouldBeRunning = false;
    private Boolean isRunning = false;
    private Context appContext = null;
    private SparseArray<MIDIStream> streams;
    private SparseArray<MIDIStream> pendingStreams;
    private ArrayMap<String,Bundle> failedConnections;

    public String bonjourName = Build.MODEL;
    public InetAddress bonjourHost = null;
    public InetAddress netmask = null;

    public int bonjourPort = 0;

    public int port;
    public int ssrc;
    private int readyState;
    private Boolean registered_eb = false;
    private Boolean published_bonjour = false;
    private Boolean initialized = false;
    private Boolean started = false;

    private int lastMessageTime;
    private int rate;
    private final long startTime;
    private final long startTimeHR;

    private MIDIPort controlChannel;
    private MIDIPort messageChannel;

    private NsdManager mNsdManager;
    private NsdManager.ResolveListener mResolveListener;
    private NsdManager.DiscoveryListener mDiscoveryListener;
    private NsdManager.RegistrationListener mRegistrationListener;
    private NsdServiceInfo serviceInfo;

    private boolean autoReconnect = false;

    public void init(Context context) {
        if(started) {
            return;
        }
        this.appContext = context;
        if(!registered_eb) {
            EventBus.getDefault().register(this);
            registered_eb = true;
        }
        if(!initialized) {
            // setupWaspDB(); // Replaced with direct SharedPreferences usage or a new setup method if complex
            // For now, assume SharedPreferences is accessed directly when needed.
            // Post AddressBookReadyEvent if other components rely on it after init.
            EventBus.getDefault().post(new AddressBookReadyEvent());
            Log.d(TAG, "AddressBook (Prefs) initialized, AddressBookReadyEvent posted.");
            initialized = true;
        }
    }

    public void start(Context context) {
        init(context);
        start();
    }

    public void start() {
        setupNetworkListener();
        if(!isOnline()) {
            Log.d(TAG,"MIDI Start : not online");
            shouldBeRunning = true;
            return;
        }
        if(this.appContext == null) {
            Log.d(TAG,"MIDI Start : ctx is null");
            return;
        }
        if(!registered_eb) {
            EventBus.getDefault().register(this);
            registered_eb = true;
        }
        try {
            this.bonjourHost = InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
//        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP) {
//
//            this.bonjourHost = getWifiAddressNew();
//        } else {
            this.bonjourHost = getWifiAddress();
//        }
        this.bonjourPort = this.port;
        controlChannel = MIDIPort.newUsing(this.port);
        controlChannel.start();
        messageChannel = MIDIPort.newUsing(this.port+1);
        messageChannel.start();

        this.streams = new SparseArray<>(2);
        this.pendingStreams = new SparseArray<>(2);
        this.failedConnections = new ArrayMap<>(2);
        try {
            initializeResolveListener(); // Ensure this is called before discovery uses mResolveListener
            initializeDiscoveryListener(); // Initialize our new discovery listener
            registerService(); // Register our own service
            // After successfully registering our service, start discovering others
            discoverServices(); 
            isRunning = true;
            shouldBeRunning = false;

            EventBus.getDefault().post(new MIDISessionStartEvent());
            checkAddressBookForReconnect();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }


    public void stop() {
        if(streams != null) {
            for (int i = 0; i < streams.size(); i++) {
                streams.get(streams.keyAt(i)).sendEnd();
            }
        }
        if(pendingStreams != null) {
            for (int i = 0; i < pendingStreams.size(); i++) {
                pendingStreams.get(pendingStreams.keyAt(i)).sendEnd();
            }
        }

        if(controlChannel != null) {
            controlChannel.stop(); // Sets isListening to false and wakes up selector
            controlChannel.close(); // Redundant isListening = false, but good for clarity and future changes
        }
        if(messageChannel != null) {
            messageChannel.stop(); // Sets isListening to false and wakes up selector
            messageChannel.close(); // Redundant isListening = false, but good for clarity and future changes
        }
        isRunning = false;
        stopDiscovery(); // Stop discovering other services
        shutdownNSDListener(); // Unregister our own service
        EventBus.getDefault().post(new MIDISessionStopEvent());
        // EventBus unregistration and network listener removal should be handled
        // by the component managing MIDISession instance, if this is a shared instance.
        // For now, keeping them in a separate cleanup method or if instance is truly ending.
    }

    /**
     * Call this method when the MIDISession instance is no longer needed and is being destroyed.
     * This ensures all resources are released.
     */
    public void cleanup() {
        Log.d(TAG, "MIDISession cleanup called.");
        stop(); // Ensures ports are stopped and closed
        removeNetworkListener();
        if (registered_eb) {
            EventBus.getDefault().unregister(this);
            registered_eb = false;
            Log.d(TAG, "EventBus unregistered.");
        }
        // WaspDB cleanup if necessary, though typically managed by its own lifecycle
        // if (db != null) {
            // db.close(); // If WaspDB has a close method
            // Log.d(TAG, "WaspDB resources would be released here if applicable.");
        // }
        // No specific SharedPreferences cleanup needed here beyond context handling by Android
        midiSessionInstance = null; // Allow for potential re-creation if needed later
    }

    // Removed finalize() method. Cleanup should be explicit via cleanup().

    public void connect(final Bundle rinfo) {
        if(isRunning) {
            if(!isAlreadyConnected(rinfo)) {
                Log.d(TAG,"opening connection to "+rinfo);
                MIDIStream stream = new MIDIStream();

                if(failedConnections.containsKey(rinfoToKey(rinfo)))  {
                    Bundle reconnectRinfo = failedConnections.get(rinfoToKey(rinfo));
                    if(reconnectRinfo.getInt(RINFO_FAIL,0) > 3) {
                        Log.d(TAG,"failed more than 3 times...");
                        return;
                    }

                    stream.connect(reconnectRinfo);
                } else {
                    stream.connect(rinfo);
                }
                Log.d(TAG,"put "+stream.initiator_token+" in pendingStreams");
                pendingStreams.put(stream.initiator_token, stream);
            } else {
                Log.e(TAG,"already have open session to "+rinfo.toString());
            }
        } else {
            Log.e(TAG,"MIDI not running");
        }
    }

    public void disconnect(Bundle rinfo) {
        Log.d(TAG,"disconnect "+rinfo);
        MIDIStream s = getStream(rinfo);
        if(s != null) {
            Log.d(TAG,"stream to disconnect : "+s.ssrc);
            s.sendEnd();
        } else {
            Log.e(TAG,"didn't find stream");

        }
    }

    public void disconnect(int remote_ssrc) {
        if(remote_ssrc != 0) {
            streams.get(remote_ssrc).disconnect();
            streams.get(remote_ssrc).shutdown();
            streams.remove(remote_ssrc);
        }

    }

    private MIDIStream getStream(Bundle rinfo) {
        for (int i = 0; i < streams.size(); i++) {
            MIDIStream s = streams.get(streams.keyAt(i));
            if(s.connectionMatch(rinfo)) {
                return s;
            }
        }
        return null;
    }

    public void setAutoReconnect(boolean b) {
        autoReconnect = b;
    }

    public boolean getAutoReconnect() {
        return autoReconnect;
    }

    private Boolean isAlreadyConnected(Bundle rinfo) {
        Log.d(TAG,"isAlreadyConnected "+pendingStreams.size()+" "+streams.size());
        boolean existsInPendingStreams = false;
        boolean existsInStreams = false;
        Log.e(TAG,"checking pendingStreams... ("+pendingStreams.size()+") "+rinfo.toString());
        for (int i = 0; i < pendingStreams.size(); i++) {
            MIDIStream ps = pendingStreams.get(pendingStreams.keyAt(i));
            if((ps != null) && ps.connectionMatch(rinfo)) {
                existsInPendingStreams = true;
                break;
            }
        }

        if(!existsInPendingStreams) {
            for (int i = 0; i < streams.size(); i++) {
                MIDIStream s = streams.get(streams.keyAt(i));
                if(s == null) {
                    Log.e(TAG,"error in isAlreadyConnected "+i+" rinfo "+rinfo.toString());
                } else {
                    Log.e(TAG,"checking streams...");
                    if(streams.get(streams.keyAt(i)).connectionMatch(rinfo)) {
                        existsInStreams = true;
                    }
                }
            }
        }
        Log.d(TAG,"existsInPendingStreams:"+(existsInPendingStreams ? "YES" : "NO"));
        Log.d(TAG,"existsInStreams:"+(existsInStreams ? "YES" : "NO"));
        return (existsInPendingStreams || existsInStreams);
//        for (int i = 0; i < streams.size(); i++) {
////            streams.get(streams.keyAt(i)).sendMessage(message);
////            String key = ((MIDIStream)streams.keyAt(i));
////            Bundle b = (MIDIStream)streams. .getRinfo1();
//            Bundle b = streams.get(streams.keyAt(i)).getRinfo1();
//            if(b.getString(MIDIConstants.RINFO_ADDR).equals(rinfo.getString(MIDIConstants.RINFO_ADDR)) && b.getInt(MIDIConstants.RINFO_PORT) == rinfo.getInt(MIDIConstants.RINFO_PORT)) {
//                return true;
//            }
//        }
//        return false;
    }

    public void sendUDPMessage(MIDIControl control, Bundle rinfo) {
        if(control != null && rinfo != null) {
            Log.d("MIDISession", "sendUDPMessage:control " + rinfo.toString());

            if (rinfo.getInt(com.disappointedpig.midi.MIDIConstants.RINFO_PORT) % 2 == 0) {
                Log.d("MIDISession", "sendUDPMessage control 5004 rinfo:" + rinfo.toString());
//            controlChannel.sendMidi(control, rinfo);
                controlChannel.sendMidi(control, rinfo);
            } else {
                Log.d("MIDISession", "sendUDPMessage control 5005 rinfo:" + rinfo.toString());
//            messageChannel.sendMidi(control, rinfo);
                messageChannel.sendMidi(control, rinfo);
            }
        } else {
            Log.e(TAG,"rinfo or control was null...");
        }
    }

    public void sendUDPMessage(MIDIMessage m, Bundle rinfo) {
        Log.d("MIDISession","sendUDPMessage:message "+rinfo.toString());
        if(m != null && rinfo != null) {
            if (rinfo.getInt(com.disappointedpig.midi.MIDIConstants.RINFO_PORT) % 2 == 0) {
                Log.d("MIDISession", "sendUDPMessage message 5004 rinfo:" + rinfo.toString());
                controlChannel.sendMidi(m, rinfo);
            } else {
                Log.d("MIDISession", "sendUDPMessage message 5004 rinfo:" + rinfo.toString());
                messageChannel.sendMidi(m, rinfo);
            }
        }
    }

    public void sendMessage(Bundle m) {
        if(published_bonjour && streams.size() > 0) {
//            Log.d("MIDISession", "sendMessage c:"+m.getInt("command",0x09)+" ch:"+m.getInt("channel",0)+" n:"+m.getInt("note",0)+" v:"+m.getInt("velocity",0));

            MIDIMessage message = new MIDIMessage();
            message.createNote(
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_COMMAND,0x09),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_CHANNEL,0),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_NOTE,0),
                    m.getInt(com.disappointedpig.midi.MIDIConstants.MSG_VELOCITY,0));
            message.ssrc = this.ssrc;

            for (int i = 0; i < streams.size(); i++) {
                streams.get(streams.keyAt(i)).sendMessage(message);
            }
        }
    }

    public void sendMessage(int note, int velocity) {
        if(published_bonjour && streams.size() > 0) {
//            Log.d("MIDISession", "note:" + note + " velocity:" + velocity);

            MIDIMessage message = new MIDIMessage();
            message.createNote(note, velocity);
            message.ssrc = this.ssrc;

            for (int i = 0; i < streams.size(); i++) {
                streams.get(streams.keyAt(i)).sendMessage(message);
            }
        }
    }

    // TODO : figure out what this is supposed to return... becuase I don't think this is right
    // getNow returns a unix (long)timestamp
    public long getNow() {
        long hrtime = System.nanoTime()-this.startTimeHR;
        long result = Math.round((hrtime / 1000L / 1000L / 1000L) * this.rate) ;
        return result;
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onAddressBookReadyEvent(AddressBookReadyEvent event) {
        Log.d(TAG,"Addressbook ready");
        checkAddressBookForReconnect();
        dumpAddressBook();
    }

    // streamConnectedEvent is called when client initiates connection... ...
    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onStreamConnected(StreamConnectedEvent e) {
        Log.d("MIDISession","StreamConnectedEvent");
        Log.d(TAG,"get "+e.initiator_token+" from pendingStreams");
        MIDIStream stream = pendingStreams.get(e.initiator_token);

        if(stream != null) {
            Log.d(TAG,"put "+e.initiator_token+" in  streams");
            streams.put(stream.ssrc, stream);
        }
        Log.d(TAG,"remove "+e.initiator_token+" from pendingStreams");

        pendingStreams.delete(e.initiator_token);
//        EventBus.getDefault().post(new MIDIConnectionEstablishedEvent());
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onMIDI2ListeningEvent(ListeningEvent e) {

    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onSyncronizeStartedEvent(SyncronizeStartedEvent e) {
//        Log.d("MIDISession","SyncronizeStartedEvent");

        EventBus.getDefault().post(new MIDISyncronizationStartEvent(e.rinfo));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onSyncronizeStoppedEvent(SyncronizeStoppedEvent e) {
//        Log.d("MIDISession","SyncronizeStoppedEvent");
        EventBus.getDefault().post(new MIDISyncronizationCompleteEvent(e.rinfo));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onConnectionEstablishedEvent(ConnectionEstablishedEvent e) {
        if(DEBUG) {
            Log.d("MIDISession", "ConnectionEstablishedEvent");
        }
        EventBus.getDefault().post(new MIDIConnectionEstablishedEvent(e.rinfo));
        addToAddressBook(e.rinfo);

    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onPacketEvent(PacketEvent e) {
        Log.d("MIDISession","PacketEvent packet from "+e.getAddress().getHostAddress()+":"+e.getPort());

        // try control first
        MIDIControl applecontrol = new MIDIControl();
        MIDIMessage message = new MIDIMessage();

        if(applecontrol.parse(e)) {
            if(DEBUG) {
                Log.d("MIDISession", "- parsed as apple control packet");
            }
            if(applecontrol.isValid()) {
//                applecontrol.dumppacket();

                if(applecontrol.initiator_token != 0) {
                    MIDIStream pending = pendingStreams.get(applecontrol.initiator_token);
                    if (pending != null) {
                        if(DEBUG) {
                            Log.d("MIDISession", " - got pending stream by token");
                        }
                        pending.handleControlMessage(applecontrol, e.getRInfo());
                        return;
                    }
                }
                // check if this applecontrol.ssrc is known stream
                MIDIStream stream = streams.get(applecontrol.ssrc);

                if(stream == null) {
                    // else, check if this is an invitation
                    //       create stream and tell stream to handle invite
                    if(DEBUG) {
                        Log.d("MIDISession", "- create new stream "+applecontrol.ssrc);
                    }
                    stream = new MIDIStream();
                    streams.put(applecontrol.ssrc, stream);
                } else {
                    if(DEBUG) {
                        Log.d("MIDISession", " - got existing stream by ssrc " + applecontrol.ssrc);
                    }

                }
                if(DEBUG) {
                    Log.d("MIDISession", "- pass control packet to stream");
                }

                stream.handleControlMessage(applecontrol, e.getRInfo());
            }
            // control packet
        } else {
//            Log.d("MIDISession","message?");
            message.parseMessage(e);
            if(message.isValid()) {
                EventBus.getDefault().post(new MIDIReceivedEvent(message.toBundle()));
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onStreamDisconnectEvent(StreamDisconnectEvent e) {
        if(DEBUG) {
            Log.d(TAG,"onStreamDisconnectEvent - ssrc:"+e.stream_ssrc+" it:"+e.initiator_token+" #streams:"+streams.size()+" #pendstreams:"+pendingStreams.size());
        }
        MIDIStream a = streams.get(e.stream_ssrc,null);

        if(a == null) {
            Log.d(TAG,"can't find stream with ssrc "+e.stream_ssrc);
        } else {
            Bundle rinfo = (Bundle) a.getRinfo1().clone();
            a.shutdown();
            streams.delete(e.stream_ssrc);
            checkAddressBookForReconnect();

//            if(rinfo.getBoolean(MIDIConstants.RINFO_RECON,false)) {
//                Log.d(TAG,"will try reconnect to "+rinfo.getString(RINFO_ADDR));
//                connect(rinfo);
//            } else {
//                Log.d(TAG,"will not reconnect to "+rinfo.getString(RINFO_ADDR));
//            }
//            if(autoReconnect) {
//                connect(rinfo);
//            }
        }
        if(e.initiator_token != 0) {
            MIDIStream p = pendingStreams.get(e.initiator_token,null);
            if(p == null) {
                Log.d(TAG,"can't find pending stream with IT "+e.initiator_token);
            } else {
                p.shutdown();
                pendingStreams.delete(e.initiator_token);
            }
        }
        if(e.rinfo != null) {
            EventBus.getDefault().post(new MIDIConnectionEndEvent((Bundle)e.rinfo.clone()));
        }

        if(DEBUG) {
            Log.d(TAG,"                     - ssrc:"+e.stream_ssrc+" it:"+e.initiator_token+" #streams:"+streams.size()+" #pendstreams:"+pendingStreams.size());
        }
    }

    @Subscribe
    public void onConnectionFailedEvent(ConnectionFailedEvent e) {
        Log.d(TAG,"onConnectionFailedEvent");
        switch(e.code) {
            case REJECTED_INVITATION:
                Log.d(TAG,"...REJECTED_INVITATION initiator_code "+e.initiator_code);
                break;
            case SYNC_FAILURE:
                Log.d(TAG,"...SYNC_FAILURE initiator_code "+e.initiator_code);
                break;
            case UNABLE_TO_CONNECT:
                Log.d(TAG,"...UNABLE_TO_CONNECT initiator_code "+e.initiator_code);
                break;
            case CONNECTION_LOST:
                Log.d(TAG,"...CONNECTION_LOST initiator_code "+e.initiator_code);
                break;
            default:
                break;

        }
        pendingStreams.delete(e.initiator_code);

        String key = rinfoToKey(e.rinfo);
        if(failedConnections.containsKey(key)) {
            Bundle r = failedConnections.get(key);
            int fail = r.getInt(RINFO_FAIL,0);
            r.putInt(RINFO_FAIL,fail+1);
            failedConnections.put(key,r);
            Log.d(TAG," rinfo: "+r.toString());
        } else {
            e.rinfo.putInt(RINFO_FAIL,1);
            failedConnections.put(key, e.rinfo);
            Log.d(TAG," rinfo: "+e.rinfo.toString());

        }
        checkAddressBookForReconnect();
    }

//    @TargetApi(21)
//    public InetAddress getWifiAddressNew() {
//
//
//        InetAddress a = null;
//        WifiManager wm = (WifiManager) appContext.getSystemService(WIFI_SERVICE);
//        Network network = wm.getCurrentNetwork();
//        ConnectivityManager cm = (ConnectivityManager)
//                appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
//        NetworkInfo networkInfo = cm.getActiveNetworkInfo();
//
//
//        if(networkInfo.getType() == ConnectivityManager.TYPE_WIFI) {
//            Network network = cm.getActiveNetwork();
//            LinkProperties prop = cm.getLinkProperties();
//
//        }
//        //        LinkProperties prop = cm.getLinkProperties(wifiInfo);
//
//        Iterator<InetAddress> dns = prop.getDnsServers().iterator();
//        while (dns.hasNext()) {
//            Log.d(TAG,"DNS: "+dns.next().getHostAddress());
//        }
//
//        Log.d(TAG,"DNS: "+prop.getDnsServers());
//        Log.d(TAG,"domains: "+prop.getDomains());
//        Log.d(TAG,"imterface: "+prop.getInterfaceName());
//        Log.d(TAG,"string: "+prop.toString());
//        Log.d(TAG,"mask: "+prop.);
//
//        Iterator<LinkAddress> iter = prop.getLinkAddresses().iterator();
//        while(iter.hasNext()) {
//            a = iter.next().getAddress();
//            Log.d(TAG,"address: "+a.getHostAddress());
//        }
//        return a;
//
//    }

    public InetAddress getWifiAddress() {
        try {
            if(appContext == null) {
                return InetAddress.getByName("127.0.0.1");
            }
            DhcpInfo dhcpInfo;
            WifiManager wm = (WifiManager) appContext.getSystemService(WIFI_SERVICE);

            dhcpInfo=wm.getDhcpInfo();

            Log.d(TAG,"DNS 1: "+intToIp(dhcpInfo.dns1));
            Log.d(TAG,"DNS 2: "+intToIp(dhcpInfo.dns2));
            Log.d(TAG,"Gateway: "+intToIp(dhcpInfo.gateway));
            Log.d(TAG,"ip Address: "+intToIp(dhcpInfo.ipAddress));
            Log.d(TAG,"lease time: "+intToIp(dhcpInfo.leaseDuration));
            Log.d(TAG,"mask: "+dhcpInfo.netmask);

            Log.d(TAG,"server ip: "+intToIp(dhcpInfo.serverAddress));


//
//            vIpAddress="IP Address: "+intToIp(dhcpInfo.ipAddress);
//            vLeaseDuration="Lease Time: "+String.valueOf(dhcpInfo.leaseDuration);
//            vNetmask="Subnet Mask: "+intToIp(dhcpInfo.netmask);
//            vServerAddress="Server IP: "+intToIp(dhcpInfo.serverAddress);


            byte[] ipbytearray= BigInteger.valueOf(wm.getConnectionInfo().getIpAddress()).toByteArray();
            reverseByteArray(ipbytearray);
            if(ipbytearray.length != 4) {
                return InetAddress.getByName("127.0.0.1");
            }

            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while(nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Iterator<InterfaceAddress> intAs = ni.getInterfaceAddresses().iterator();
                while(intAs.hasNext()) {
                    InterfaceAddress ia = intAs.next();
                    Log.d(TAG," ia: "+ia.getAddress().getHostAddress());
                    if(sameIP(ia.getAddress(),InetAddress.getByAddress(ipbytearray))) {
                        Log.d(TAG, "same!!! " + ia.getAddress().getHostAddress() + "/" + ia.getNetworkPrefixLength());
                        Log.d(TAG, "netmask: "+ intToIp(prefixLengthToNetmaskInt(ia.getNetworkPrefixLength())));
                        netmask = InetAddress.getByName(intToIp(prefixLengthToNetmaskInt(ia.getNetworkPrefixLength())));
                    }
                }

//                Enumeration<InetAddress> inetAs = ni.getInetAddresses();
//                while(inetAs.hasMoreElements()) {
//                    InetAddress addr = inetAs.nextElement();
//                    Log.d(TAG, " ia: "+addr.getHostAddress());
//                    if(sameIP(addr,InetAddress.getByAddress(ipbytearray))) {
//                        Log.d(TAG,"same!!! "+ni.getDisplayName() + "  "+ni.toString());
//
//                    }
//                }

            }

//            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(InetAddress.getByAddress(ipbytearray));
//            for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
//
//                    networkInterface.isLoopback()
//                    Log.d(TAG, "network prefix: " + address.getNetworkPrefixLength());
//            }

//            if(ipbytearray != null && ipbytearray.length > 0) {
//                Log.d(TAG, "new netmask: " + intToIp(prefixLengthToNetmaskInt(getNetmask(InetAddress.getByAddress(ipbytearray)))));
//            }

            return InetAddress.getByAddress(ipbytearray);
        } catch (UnknownHostException e) {
            return null;
        } catch (SocketException e) {
            e.printStackTrace();
            return null;
        } catch (NullPointerException e) {
            e.printStackTrace();
            return null;
        }
    }

//    public String getLocalIpAddress() {
//        try {
//            for (Enumeration<NetworkInterface> en = NetworkInterface
//                    .getNetworkInterfaces(); en.hasMoreElements();) {
//                NetworkInterface intf = en.nextElement();
//                for (Enumeration<InetAddress> enumIpAddr = intf
//                        .getInetAddresses(); enumIpAddr.hasMoreElements();) {
//                    InetAddress inetAddress = enumIpAddr.nextElement();
//                    System.out.println("ip1--:" + inetAddress);
//                    System.out.println("ip2--:" + inetAddress.getHostAddress());
//
//                    // for getting IPV4 format
//                    if (!inetAddress.isLoopbackAddress() && InetAddressUtils.isIPv4Address(ipv4 = inetAddress.getHostAddress())) {
//
//                        String ip = inetAddress.getHostAddress().toString();
//                        System.out.println("ip---::" + ip);
////                        EditText tv = (EditText) findViewById(R.id.ipadd);
////                        tv.setText(ip);
//                        // return inetAddress.getHostAddress().toString();
//                        return ip;
//                    }
//                }
//            }
//        } catch (Exception ex) {
//            Log.e("IP Address", ex.toString());
//        }
//        return null;
//    }

    public int prefixLengthToNetmaskInt(int prefixLength)
            throws IllegalArgumentException {
        Log.d(TAG,"prefixLengthToNetmaskInt:"+prefixLength);
        if (prefixLength < 0 || prefixLength > 32) {
//            throw new IllegalArgumentException("Invalid prefix length (0 <= prefix <= 32)");
            return 0;

        }
        int value = 0xffffffff << (32 - prefixLength);
        return Integer.reverseBytes(value);
    }

    public int getNetmask(InetAddress addr) {
        try {
            NetworkInterface networkInterface = NetworkInterface.getByInetAddress(addr);
            Log.d(TAG,"    interface: "+addr.getHostAddress());
            for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) {
                Log.d(TAG,"    "+address.getAddress().getHostAddress() + "/" +address.getNetworkPrefixLength());

                int netPrefix = address.getNetworkPrefixLength();
                return netPrefix;
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public String intToIp(int i) {
        i = Integer.reverseBytes(i);
        return ((i >> 24 ) & 0xFF ) + "." +
                ((i >> 16 ) & 0xFF) + "." +
                ((i >> 8 ) & 0xFF) + "." +
                ( i & 0xFF) ;
    }

    private static void reverseByteArray(byte[] array) {
        if (array == null) {
            return;
        }
        int i = 0;
        int j = array.length - 1;
        byte tmp;
        while (j > i) {
            tmp = array[j];
            array[j] = array[i];
            array[i] = tmp;
            j--;
            i++;
        }
    }


    // --------------------------------------------
    // bonjour stuff
    //

    public void setBonjourName(String name) {
        this.bonjourName = name;
    }

    private void registerService() throws UnknownHostException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            // Create the NsdServiceInfo object, and populate it.
            serviceInfo = new NsdServiceInfo();

            // The name is subject to change based on conflicts
            // with other services advertised on the same network.

            serviceInfo.setServiceName(this.bonjourName);
            serviceInfo.setServiceType(BONJOUR_TYPE);
            serviceInfo.setHost(this.bonjourHost);
            serviceInfo.setPort(this.bonjourPort);

//            if(DEBUG) {
//                Log.d(TAG,"register service: "+serviceInfo.toString());
//            }
            mNsdManager = (NsdManager) appContext.getApplicationContext().getSystemService(Context.NSD_SERVICE);

            initializeNSDRegistrationListener();

            mNsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, mRegistrationListener);
//            mNsdManager.resolveService(serviceInfo, mResolveListener);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void initializeNSDRegistrationListener() {
        mRegistrationListener = new NsdManager.RegistrationListener() {

            @Override
            public void onServiceRegistered(NsdServiceInfo NsdServiceInfo) {
                // Save the service name.  Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                Log.d(TAG,"Service Registered "+NsdServiceInfo.toString());
                if(NsdServiceInfo.getServiceName() != null && bonjourName != NsdServiceInfo.getServiceName()) {
                    bonjourName = NsdServiceInfo.getServiceName();
                    serviceInfo.setServiceName(bonjourName);

//                    mNsdManager.resolveService(serviceInfo, mResolveListener);

                }
//                mNsdManager.resolveService(serviceInfo, mResolveListener);

                published_bonjour = true;
                EventBus.getDefault().post(new MIDISessionNameRegisteredEvent());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Registration failed!  Put debugging code here to determine why.
                System.out.print("onRegistrationFailed \n"+serviceInfo.toString()+"\nerror code: "+errorCode);
                published_bonjour = false;
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo arg0) {
                // Service has been unregistered.  This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
                System.out.print("onServiceUnregistered ");
                published_bonjour = false;
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                // Unregistration failed.  Put debugging code here to determine why.
                System.out.print("onUnregistrationFailed ");
                published_bonjour = false;
            }
        };
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void initializeResolveListener() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            mResolveListener = new NsdManager.ResolveListener() {

                @Override
                public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    // Called when the resolve fails.  Use the error code to debug.
                    Log.e(TAG, "Resolve failed" + errorCode);
                }

                @Override
                public void onServiceResolved(NsdServiceInfo serviceInfo) {
                    if (serviceInfo == null) {
                        Log.w(TAG, "Resolved service info is null.");
                        return;
                    }
                    InetAddress host = serviceInfo.getHost();
                    int port = serviceInfo.getPort();
                    String serviceName = serviceInfo.getServiceName();

                    if (host != null && port > 0) {
                        Log.i(TAG, "Service Resolved: Name: " + serviceName + ", Host: " + host.getHostAddress() + ", Port: " + port);
                        EventBus.getDefault().post(new MIDIServiceDiscoveredEvent(serviceName, host, port));
                    } else {
                        Log.w(TAG, "Resolved service but host or port is invalid. Name: " + serviceName + " Host: " + host + " Port: " + port);
                    }
                }
            };
        }
    }

    private void shutdownNSDListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            if (mNsdManager != null) {
                // Stop service registration
                try {
                    if (mRegistrationListener != null) { // Check if listener is not null
                        mNsdManager.unregisterService(mRegistrationListener);
                        Log.d(TAG, "Service unregistered.");
                    }
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Error unregistering service: " + e.getMessage());
                }
                // Note: stopDiscovery() is called separately in stop() method now.
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void initializeDiscoveryListener() {
        mDiscoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onDiscoveryStarted(String regType) {
                Log.d(TAG, "Service discovery started: " + regType);
            }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                Log.i(TAG, "Service found: " + service.getServiceName() + " type: " + service.getServiceType());
                if (service.getServiceType().equals(BONJOUR_TYPE)) {
                    // Optionally, filter out own service if bonjourName is known and matches service.getServiceName()
                    // if (service.getServiceName().equals(bonjourName)) {
                    //    Log.d(TAG, "Own service found, not resolving.");
                    // } else {
                    Log.d(TAG, "Resolving service: " + service.getServiceName());
                    if (mNsdManager != null && mResolveListener != null) { // Ensure mResolveListener is not null
                        mNsdManager.resolveService(service, mResolveListener);
                    } else {
                        Log.w(TAG, "Cannot resolve service, NsdManager or mResolveListener is null.");
                    }
                    // }
                }
            }

            @Override
            public void onServiceLost(NsdServiceInfo service) {
                Log.e(TAG, "Service lost: " + service.getServiceName());
                // TODO: Handle service lost (e.g., remove from a list of available services)
                // For now, just log. This might require an EventBus event.
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.i(TAG, "Discovery stopped: " + serviceType);
            }

            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed to start: Error code " + errorCode + " for type " + serviceType);
                // It's generally not recommended to call stopServiceDiscovery from within onStartDiscoveryFailed
                // as it can lead to a loop if starting always fails.
                // Consider a retry mechanism with backoff or disabling discovery for a period.
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery failed to stop: Error code " + errorCode + " for type " + serviceType);
            }
        };
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public void discoverServices() {
        if (mNsdManager != null && mDiscoveryListener != null && isRunning) {
            Log.d(TAG, "Starting service discovery for type: " + BONJOUR_TYPE);
            try {
                mNsdManager.discoverServices(BONJOUR_TYPE, NsdManager.PROTOCOL_DNS_SD, mDiscoveryListener);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Error starting service discovery: " + e.getMessage());
            }
        } else {
            Log.w(TAG, "Cannot start discovery - NsdManager, listener not initialized, or session not running.");
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public void stopDiscovery() {
        if (mNsdManager != null && mDiscoveryListener != null) {
            Log.d(TAG, "Stopping service discovery.");
            try {
                mNsdManager.stopServiceDiscovery(mDiscoveryListener);
            } catch (IllegalArgumentException e) {
                // This can happen if discovery was not started or already stopped.
                Log.w(TAG, "Error stopping service discovery: " + e.getMessage());
            }
        }
    }

    public String version() {
        return BuildConfig.VERSION_NAME;
    }

    // TODO : make this actually work...
    boolean isHostConnectionAllowed(Bundle rinfo) {
        return true;
    }


    // -------------------------------------------------
    // SharedPreferences based Address Book implementation

    // setupWaspDB is removed. Initialization of SharedPreferences access will be on-demand.

    public Bundle getEntryFromAddressBook(String key) {
        if (appContext == null || key == null) return null;
        SharedPreferences prefs = appContext.getSharedPreferences(MIDI_ADDRESS_BOOK_PREFS, Context.MODE_PRIVATE);
        // Check if a primary field like address exists for this key.
        // Using a specific field like "_address" helps differentiate from other potential prefs.
        String addressKey = key + "_address";
        if (!prefs.contains(addressKey)) {
            return null;
        }

        Bundle rinfo = new Bundle();
        rinfo.putString(MIDIConstants.RINFO_ADDR, prefs.getString(addressKey, null));
        rinfo.putInt(MIDIConstants.RINFO_PORT, prefs.getInt(key + "_port", 0));
        rinfo.putBoolean(MIDIConstants.RINFO_RECON, prefs.getBoolean(key + "_reconnect", false));
        rinfo.putString(MIDIConstants.RINFO_NAME, prefs.getString(key + "_name", "")); // Assuming name is stored

        return rinfo;
    }

    public boolean addToAddressBook(Bundle rinfo) {
        if (appContext == null || rinfo == null) return false;
        String key = rinfoToKey(rinfo);
        if (key == null) return false;

        SharedPreferences prefs = appContext.getSharedPreferences(MIDI_ADDRESS_BOOK_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putString(key + "_address", rinfo.getString(MIDIConstants.RINFO_ADDR));
        editor.putInt(key + "_port", rinfo.getInt(MIDIConstants.RINFO_PORT));
        editor.putBoolean(key + "_reconnect", rinfo.getBoolean(MIDIConstants.RINFO_RECON, false));
        editor.putString(key + "_name", rinfo.getString(MIDIConstants.RINFO_NAME, "")); // Store name

        boolean status = editor.commit(); // Using commit for immediate result, can switch to apply()
        
        if (status) {
            Log.d(TAG, "addToAddressBook (Prefs): " + key + " Name: " + rinfo.getString(MIDIConstants.RINFO_NAME, ""));
            EventBus.getDefault().post(new MIDIAddressBookEvent());
            dumpAddressBook();
        }
        return status;
    }

    private String rinfoToKey(Bundle rinfo) {
        if (rinfo == null) return null;
        String address = rinfo.getString(RINFO_ADDR);
        int port = rinfo.getInt(RINFO_PORT, 0);
        if (address == null || address.isEmpty() || port == 0) return null;
        return String.format(Locale.ENGLISH,"%s:%d", address, port);
    }

    // Overload for MIDIAddressBookEntry for convenience if used elsewhere
    public boolean addToAddressBook(MIDIAddressBookEntry entry) {
        if (entry == null) return false;
        return addToAddressBook(entry.rinfo());
    }
    
    public boolean deleteFromAddressBookByKey(String key) {
        if (appContext == null || key == null) return false;
        SharedPreferences prefs = appContext.getSharedPreferences(MIDI_ADDRESS_BOOK_PREFS, Context.MODE_PRIVATE);
        Log.d(TAG, "deleteFromAddressBookByKey (Prefs): " + key);
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(key + "_address");
        editor.remove(key + "_port");
        editor.remove(key + "_reconnect");
        editor.remove(key + "_name");
        return editor.commit(); // Using commit for immediate result
    }

    public boolean deleteFromAddressBook(MIDIAddressBookEntry m) {
        if (m == null) return false;
        return deleteFromAddressBookByKey(rinfoToKey(m.rinfo()));
    }

    public boolean addressBookIsEmpty() {
        if (appContext == null) return true;
        SharedPreferences prefs = appContext.getSharedPreferences(MIDI_ADDRESS_BOOK_PREFS, Context.MODE_PRIVATE);
        return prefs.getAll().isEmpty(); // This might not be entirely accurate if unrelated keys are present
                                        // A more robust check would iterate and see if any keys match our pattern.
                                        // For now, this is a simpler approximation.
    }

    public ArrayList<MIDIAddressBookEntry> getAllAddressBook() {
        ArrayList<MIDIAddressBookEntry> list = new ArrayList<>();
        if (appContext == null) return list;

        SharedPreferences prefs = appContext.getSharedPreferences(MIDI_ADDRESS_BOOK_PREFS, Context.MODE_PRIVATE);
        Map<String, ?> allEntries = prefs.getAll();
        java.util.Set<String> baseKeys = new java.util.HashSet<>();

        for (String mapKey : allEntries.keySet()) {
            if (mapKey.endsWith("_address")) { // Identify our entries by a known suffix
                baseKeys.add(mapKey.substring(0, mapKey.lastIndexOf("_address")));
            }
        }

        for (String baseKey : baseKeys) {
            Bundle rinfoBundle = new Bundle();
            rinfoBundle.putString(MIDIConstants.RINFO_ADDR, prefs.getString(baseKey + "_address", null));
            rinfoBundle.putInt(MIDIConstants.RINFO_PORT, prefs.getInt(baseKey + "_port", 0));
            rinfoBundle.putBoolean(MIDIConstants.RINFO_RECON, prefs.getBoolean(baseKey + "_reconnect", false));
            rinfoBundle.putString(MIDIConstants.RINFO_NAME, prefs.getString(baseKey + "_name", ""));

            // Validate that essential parts were found
            if (rinfoBundle.getString(MIDIConstants.RINFO_ADDR) != null) {
                list.add(new MIDIAddressBookEntry(rinfoBundle));
            }
        }
        Log.d(TAG, "getAllAddressBook (Prefs) count: " + list.size());
        return list;
    }

    private void dumpAddressBook() {
        if (appContext == null) {
             Log.d(TAG, "-----------------MIDI Address Book (Prefs) - appContext null-------------");
            return;
        }
        ArrayList<MIDIAddressBookEntry> allEntries = getAllAddressBook();
        Log.d(TAG, "-----------------MIDI Address Book (Prefs) Dump ("+allEntries.size()+")-------------------");
        for (MIDIAddressBookEntry entry : allEntries) {
            Bundle rinfo = entry.rinfo();
            String key = rinfoToKey(rinfo);
            Log.d(TAG, " (" + key + ") : Name: " + entry.getName() + ", Addr: " + entry.getAddressPort() + ", Recon: " + entry.getReconnect());
        }
        Log.d(TAG, "--------------------------------------------------------------");
    }

    public void checkAddressBookForReconnect() {
        if (appContext == null) return;
        ArrayList<MIDIAddressBookEntry> allEntries = getAllAddressBook();
        Log.d(TAG, "-----------------Checking Address Book For Reconnect (Prefs) ("+allEntries.size()+")-------------------");
        for (MIDIAddressBookEntry entry : allEntries) {
            Log.d(TAG, " checking for reconnect - (" + rinfoToKey(entry.rinfo()) + ") : " + entry.getAddressPort() + " Recon: " + (entry.getReconnect() ? "YES" : "NO"));
            if (entry.getReconnect()) {
                // Ensure the rinfo bundle passed to connect also has the name if needed by connect logic or stream
                Bundle rinfoToConnect = entry.rinfo(); 
                // rinfoToConnect.putString(MIDIConstants.RINFO_NAME, entry.getName()); // Already in rinfo() from MIDIAddressBookEntry
                connect(rinfoToConnect); 
                // onSameNetwork check is fine as is
                if (bonjourHost != null && netmask != null && onSameNetwork(entry.getAddress())) {
                    Log.d(TAG, " same network - (" + rinfoToKey(entry.rinfo()) + ") : " + entry.getAddressPort());
                } else {
                    Log.d(TAG, " different network or network info unavailable - (" + rinfoToKey(entry.rinfo()) + ") : " + entry.getAddressPort());
                }
            }
        }
        Log.d(TAG, "----------------------------------------------------------------------");
    }

    public boolean onSameNetwork(String ip) {
        try {
            byte[] a1 = InetAddress.getByName(ip).getAddress();
            byte[] a2 = bonjourHost.getAddress();
            byte[] m = netmask.getAddress();
            for (int i = 0; i < a1.length; i++)
                if ((a1[i] & m[i]) != (a2[i] & m[i]))
                    return false;

            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }



//    public static boolean sameNetwork(String ip1, String ip2, String mask)
//            throws Exception {
//
//        byte[] a1 = InetAddress.getByName(ip1).getAddress();
//        byte[] a2 = InetAddress.getByName(ip2).getAddress();
//        byte[] m = InetAddress.getByName(mask).getAddress();
//
//        for (int i = 0; i < a1.length; i++)
//            if ((a1[i] & m[i]) != (a2[i] & m[i]))
//                return false;
//
//        return true;
//
//    }

    public boolean sameIP(InetAddress a1, InetAddress a2) {
        byte[] b1 = a1.getAddress();
        byte[] b2 = a2.getAddress();
        for (int i = 0; i < b1.length; i++)
            if (b1[i] != b2[i])
                return false;

        return true;
    }

    private BroadcastReceiver wifiReceiver;
    private boolean networkListenerRegistered = false;

    public void setupNetworkListener() {

//        if(this.wifiReceiver != null) {
//            removeNetworkListener();
//        }
        if(networkListenerRegistered) {
            removeNetworkListener();
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        this.wifiReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                // Do whatever you need it to do when it receives the broadcast
                // Example show a Toast message...
//                showSuccessfulBroadcast();
                Log.d(TAG, "wifiReceiver - "+intent.getAction());
//                checkAddressBookForReconnect();
                if(isOnline()) {
                    Log.d(TAG,"network is online");
                    if(shouldBeRunning && !isRunning) {
                        start();
                    }
                } else {
                    Log.d(TAG,"network not online");
                    if(isRunning) {
                        shouldBeRunning = true;
                        stop();
                    }
//                    shouldBeRunning = true;
//                    stop();
//
                }

            }
        };

        if(appContext != null) {
            appContext.registerReceiver(this.wifiReceiver, intentFilter);
        }
        networkListenerRegistered = true;
    }

    public void removeNetworkListener() {
        if(appContext != null && wifiReceiver != null) {
            try {
                appContext.unregisterReceiver(wifiReceiver);
                networkListenerRegistered = false;

            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        Log.d(TAG,"isOnline? "+((cm.getActiveNetworkInfo() != null &&
                cm.getActiveNetworkInfo().isConnectedOrConnecting()) ? "ON" : "OFF"));
        return cm.getActiveNetworkInfo() != null &&
                cm.getActiveNetworkInfo().isConnectedOrConnecting();
    }

}
