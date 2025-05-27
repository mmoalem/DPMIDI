package com.disappointedpig.midi.events;

import android.net.nsd.NsdServiceInfo; // Or store individual fields
import java.net.InetAddress;

public class MIDIServiceDiscoveredEvent {
    public final String serviceName;
    public final InetAddress host;
    public final int port;
    // You could also store the full NsdServiceInfo if needed later
    // public final NsdServiceInfo serviceInfo;

    public MIDIServiceDiscoveredEvent(String serviceName, InetAddress host, int port) {
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
    }
}
