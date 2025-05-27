package com.disappointedpig.midi;

import android.os.Bundle;
import android.util.Log;

import com.disappointedpig.midi.internal_events.PacketEvent;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

class MIDIPort implements Runnable {
    private int port;

    private Selector selector;
    private DatagramChannel channel;

    private Queue<DatagramPacket> outboundQueue;
//    private Queue<DatagramPacket> inboundQueue;

    private volatile boolean isListening = false;

    private static final int BUFFER_SIZE = 1536;
    private static final String TAG = "MIDIPort";
//    private static final boolean DEBUG = false;

    private final Thread thread = new Thread(this);

    static MIDIPort newUsing(int port) {
        return new MIDIPort(port);
    }

    private MIDIPort(int port) {
        this.port = port;
        try {
            selector = Selector.open();
            channel = DatagramChannel.open();
            outboundQueue = new ConcurrentLinkedQueue<>();
//            inboundQueue = new ConcurrentLinkedQueue<DatagramPacket>();

            InetSocketAddress address = new InetSocketAddress(this.port);
            channel.socket().setReuseAddress(true);
            channel.configureBlocking(false);
            channel.socket().bind(address);

//            channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, new UDPBuffer());

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        Log.d(TAG, "Closing MIDIPort " + port);
        isListening = false;
        if (selector != null && selector.isOpen()) {
            try {
                selector.wakeup();
            } catch (Exception e) { // Catches NullPointerException or ClosedSelectorException
                Log.e(TAG, "Exception during selector.wakeup() on port " + port, e);
            }
        }
        // Thread joining should ideally be managed by the creator of MIDIPort,
        // but we ensure the loop in run() terminates.
    }

    @Override
    public void run() {
        try {
            while(isListening) {
                try {
                    if (selector == null || !selector.isOpen()) {
                        Log.w(TAG, "Selector is null or closed on port " + port + ", exiting run loop.");
                        isListening = false; // Ensure loop terminates
                        break;
                    }
                    selector.select(); // Can throw IOException or ClosedSelectorException
                    if (!isListening) { // Check again after select() returns, in case close() was called
                        break;
                    }
                    Set<SelectionKey> readyKeys = selector.selectedKeys();
                    if (readyKeys.isEmpty() && isListening) { // Check isListening, select() might return if woken up
                        continue; 
                    }
                    Iterator<SelectionKey> keyIter = readyKeys.iterator();
                    while (keyIter.hasNext()) {
                        SelectionKey key = keyIter.next();
                        keyIter.remove();
                        if(!key.isValid()) {
                            continue;
                        }

                        if (key.isReadable()) {
                            handleRead(key);
                        }
                        if (key.isWritable()) {
                            handleWrite(key);
                        }
                    }
                } catch (java.nio.channels.ClosedSelectorException e) {
                    Log.w(TAG, "Selector closed on port " + port + ", exiting run loop.", e);
                    isListening = false; // Ensure loop terminates
                } catch (IOException e) {
                    if (isListening) { // Only log if we were still supposed to be listening
                        Log.e(TAG, "IOException in run loop on port " + port, e);
                    }
                    // Potentially set isListening = false here if error is critical
                }
            }
        } finally {
            Log.d(TAG, "MIDIPort run() finally block executing for port " + port);
            if (selector != null && selector.isOpen()) {
                try {
                    selector.close();
                    Log.d(TAG, "Selector closed for port " + port);
                } catch (IOException e) {
                    Log.e(TAG, "IOException while closing selector for port " + port, e);
                }
            }
            if (channel != null && channel.isOpen()) {
                try {
                    channel.close();
                    Log.d(TAG, "Channel closed for port " + port);
                } catch (IOException e) {
                    Log.e(TAG, "IOException while closing channel for port " + port, e);
                }
            }
            Log.d(TAG, "Port " + port + " resources closed.");
        }
    }

    int getPort() {
        return this.port;
    }

    boolean isListening() {
        return this.isListening;
    }

    int getThreadPriority() {
        return thread.getPriority();
    }

    void setThreadPriority(int priority) {
        thread.setPriority(priority);
    }

    void start() {
        isListening = true;

//        final Thread thread = new Thread(this);
//        // The JVM exits when the only threads running are all daemon threads.
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.start();
//        Log.d(TAG,"create thread : "+thread.getId());

    }

    void stop() {
        // This existing stop method just sets isListening to false.
        // The new close() method is more comprehensive for resource cleanup.
        // We can keep this for now, but ensure close() is called for actual cleanup.
        isListening = false;
        if (selector != null && selector.isOpen()) {
             selector.wakeup(); // Also wakeup selector here
        }
    }

    private void handleRead(SelectionKey key) {
//        Log.d("MIDIPort2","handleRead");
        DatagramChannel c = (DatagramChannel) key.channel();
        UDPBuffer b = (UDPBuffer) key.attachment();
        try {
            b.buffer.clear();
            b.socketAddress = c.receive(b.buffer);
            if (b.socketAddress != null) { // Ensure a packet was actually received
                EventBus.getDefault().post(new PacketEvent(new DatagramPacket(b.buffer.array(),b.buffer.position(),b.socketAddress))); // Use position for length
            } else {
                Log.w(TAG, "receive() returned null on port " + port);
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException in handleRead on port " + port, e);
        }
    }

    private void handleWrite(SelectionKey key) {
        if(!outboundQueue.isEmpty()) {
//            Log.d("MIDIPort2","handleWrite "+ outboundQueue.size());
            try {
                DatagramChannel c = (DatagramChannel) key.channel();
                DatagramPacket d = outboundQueue.poll();
                if (d != null) {
                    c.send(ByteBuffer.wrap(d.getData()), d.getSocketAddress());
                }
            } catch (IOException e) {
                Log.e(TAG, "IOException in handleWrite on port " + port, e);
            }
        }
    }


    void sendMidi(MIDIControl control, Bundle rinfo) {
//        Log.d("MIDIPort2","sendMidi(control)");
        if (!isListening) {
            Log.d(TAG,"not listening...");
            return;
        }
        addToOutboundQueue(control.generateBuffer(),rinfo);
    }

    void sendMidi(MIDIMessage message, Bundle rinfo) {
//        Log.d("MIDIPort","sendMidi(message)");
        if (!isListening) {
            Log.d(TAG,"not listening...");
            return;
        }
        addToOutboundQueue(message.generateBuffer(),rinfo);
    }

    private void addToOutboundQueue(byte[] data, Bundle rinfo) {
        try {
            outboundQueue.add(new DatagramPacket(data, data.length, InetAddress.getByName(rinfo.getString(com.disappointedpig.midi.MIDIConstants.RINFO_ADDR)), rinfo.getInt(com.disappointedpig.midi.MIDIConstants.RINFO_PORT)));
            if (selector != null && selector.isOpen()) {
                selector.wakeup();
            }
        } catch (UnknownHostException e) {
            Log.e(TAG, "UnknownHostException in addToOutboundQueue for port " + port, e);
        } catch (Exception e) {
            Log.e(TAG, "Exception in addToOutboundQueue for port " + port, e);
        }
    }

    private static class UDPBuffer { // Made static as it doesn't need to access MIDIPort instance fields
        // DatagramPacket datagramPacket; // This field was unused
        SocketAddress  socketAddress;
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    }
}
