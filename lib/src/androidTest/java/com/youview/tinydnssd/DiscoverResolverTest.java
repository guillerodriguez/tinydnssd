/* Copyright (c) 2015 YouView Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
 * IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.youview.tinydnssd;

import android.app.Application;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.test.ApplicationTestCase;

import org.junit.runner.RunWith;
import org.junit.runner.Runner;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.mockito.Mockito.*;

@RunWith(Runner.class)
public class DiscoverResolverTest extends ApplicationTestCase<Application> {

    private static final String SERVICE_TYPE = "_example._tcp._local";

    private DiscoverResolver mDiscoverResolver;
    private DiscoverResolver.Listener mMockListener;
    private NsdManager.DiscoveryListener mDiscoveryListener;

    public DiscoverResolverTest() {
        super(Application.class);
    }

    // Stub that allows mocking of the call to MDNSDiscover.resolve()
    interface Resolver {
        MDNSDiscover.Result resolve(String serviceName, int timeout) throws IOException;
    }

    private Resolver mMockResolver;

    // Spy this so tests can use verify() on onServicesChanged(), but have a common behaviour that
    // decrements the latch since we want the test to wait until the callback is dispatched from
    // another thread
    public class ListenerStub implements DiscoverResolver.Listener {
        @Override
        public void onServicesChanged(Map<String, MDNSDiscover.Result> services) {
            if (mLatch != null) {
                mLatch.countDown();
            }
        }
    }

    private CountDownLatch mLatch;

    public void setUp() throws IOException {
        System.setProperty("dexmaker.dexcache", getContext().getCacheDir().getPath());

        // Context is used only to access the NsdManager, which we don't want to happen since we
        // will be stubbing those methods later. Ideally we would mock NsdManager, but it is a final
        // class which cannot be mocked with Mockito.
        Context mockContext = mock(Context.class);
        when(mockContext.getSystemService(Context.NSD_SERVICE)).thenThrow(new AssertionError("did not call mock"));

        mMockListener = spy(new ListenerStub());

        mDiscoverResolver = new DiscoverResolver(mockContext, SERVICE_TYPE, mMockListener);
        mDiscoverResolver = Util.powerSpy(mDiscoverResolver);

        // Stub DiscoverResolver.discoverServices() and stopServiceDiscovery() to prevent it from
        // invoking the actual NsdManager. Also store the reference to the DiscoveryListener so we
        // can imitate NsdManager's callbacks.
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocationOnMock) {
                mDiscoveryListener = (NsdManager.DiscoveryListener) invocationOnMock.getArguments()[2];
                return null;
            }
        }).when(mDiscoverResolver).discoverServices(anyString(), anyInt(), any(NsdManager.DiscoveryListener.class));
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocationOnMock) {
                assertEquals(mDiscoveryListener, invocationOnMock.getArguments()[0]);
                return null;
            }
        }).when(mDiscoverResolver).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));

        // Stub DiscoverResolver.resolve() to delegate to mMockResolver
        mMockResolver = mock(Resolver.class);
        doAnswer(new Answer<MDNSDiscover.Result>() {
            @Override
            public MDNSDiscover.Result answer(InvocationOnMock invocation) throws Throwable {
                String serviceName = (String) invocation.getArguments()[0];
                int timeout = (Integer) invocation.getArguments()[1];
                return mMockResolver.resolve(serviceName, timeout);
            }
        }).when(mDiscoverResolver).resolve(anyString(), anyInt());
    }

    public void testDoubleStartThrowsException() {
        mDiscoverResolver.start();
        try {
            mDiscoverResolver.start();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // success
        }
    }

    public void testStopWhenNotStartedThrowsException() {
        try {
            mDiscoverResolver.stop();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            // success
        }
    }

     public void testStartStop() {
        startDiscoveryOnMainThread();
        verify(mDiscoverResolver).discoverServices(eq(SERVICE_TYPE), eq(NsdManager.PROTOCOL_DNS_SD), any(NsdManager.DiscoveryListener.class));
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        verify(mDiscoverResolver, never()).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
        stopDiscoveryOnMainThread();
        verify(mDiscoverResolver).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
    }

    // 'Debounce' tests check that start()/stop() can be safely called on the DiscoverResolver
    // without regard to the finer grained state in NsdManager, which will throw an exception if you
    // call stopServiceDiscovery() in the time between discoverServices() and onDiscoveryStarted().
    // Since this is asynchronous internally, NsdManager may even need to be restarted as soon as
    // it stops to respect the simplified state.

    public void testDebounceStartStop() {
        startDiscoveryOnMainThread();
        verify(mDiscoverResolver).discoverServices(eq(SERVICE_TYPE), eq(NsdManager.PROTOCOL_DNS_SD), any(NsdManager.DiscoveryListener.class));
        stopDiscoveryOnMainThread();
        verify(mDiscoverResolver, never()).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        verify(mDiscoverResolver).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
    }

    public void testDebounceStartStopStart() {
        startDiscoveryOnMainThread();
        verify(mDiscoverResolver).discoverServices(eq(SERVICE_TYPE), eq(NsdManager.PROTOCOL_DNS_SD), any(NsdManager.DiscoveryListener.class));
        stopDiscoveryOnMainThread();
        startDiscoveryOnMainThread();
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        verify(mDiscoverResolver, times(1)).discoverServices(anyString(), anyInt(), any(NsdManager.DiscoveryListener.class));
        verify(mDiscoverResolver, never()).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
    }

    public void testDebounceStartStopStartStop() {
        startDiscoveryOnMainThread();
        verify(mDiscoverResolver).discoverServices(eq(SERVICE_TYPE), eq(NsdManager.PROTOCOL_DNS_SD), any(NsdManager.DiscoveryListener.class));
        stopDiscoveryOnMainThread();
        startDiscoveryOnMainThread();
        stopDiscoveryOnMainThread();
        verify(mDiscoverResolver, never()).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        verify(mDiscoverResolver).stopServiceDiscovery(any(NsdManager.DiscoveryListener.class));
    }

    public void testStartAgainWhileNsdIsStopping() {
        startDiscoveryOnMainThread();
        verify(mDiscoverResolver).discoverServices(eq(SERVICE_TYPE), eq(NsdManager.PROTOCOL_DNS_SD), any(NsdManager.DiscoveryListener.class));
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        stopDiscoveryOnMainThread();
        verify(mDiscoverResolver).stopServiceDiscovery(eq(mDiscoveryListener));
        // we call start() again before onDiscoveryStopped() => must restart discovery automatically
        startDiscoveryOnMainThread();
        verify(mDiscoverResolver, times(1)).discoverServices(eq(SERVICE_TYPE), eq(NsdManager.PROTOCOL_DNS_SD), any(NsdManager.DiscoveryListener.class));
        mDiscoveryListener.onDiscoveryStopped(SERVICE_TYPE);
        verify(mDiscoverResolver, times(2)).discoverServices(eq(SERVICE_TYPE), eq(NsdManager.PROTOCOL_DNS_SD), any(NsdManager.DiscoveryListener.class));
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        stopDiscoveryOnMainThread();
        verify(mDiscoverResolver, times(2)).stopServiceDiscovery(eq(mDiscoveryListener));
    }

    public void testResolve() throws IOException, InterruptedException {
        startDiscoveryOnMainThread();
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        NsdServiceInfo serviceInfo = newNsdServiceInfo("device-1234", "_example._tcp.");
        MDNSDiscover.Result result = new MDNSDiscover.Result();
        when(mMockResolver.resolve(eq("device-1234._example._tcp.local"), anyInt())).thenReturn(result);
        mLatch = new CountDownLatch(1);
        mDiscoveryListener.onServiceFound(serviceInfo);
        mLatch.await();
        verify(mMockResolver).resolve(eq("device-1234._example._tcp.local"), anyInt());
        Map<String, MDNSDiscover.Result> expectedMap = new HashMap<>();
        expectedMap.put("device-1234._example._tcp.local", result);
        verify(mMockListener).onServicesChanged(eq(expectedMap));
        stopDiscoveryOnMainThread();
    }

    public void testServiceLost() throws IOException, InterruptedException {
        startDiscoveryOnMainThread();
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        NsdServiceInfo serviceInfo = newNsdServiceInfo("device-1234", "_example._tcp.");
        MDNSDiscover.Result result = new MDNSDiscover.Result();
        when(mMockResolver.resolve(eq("device-1234._example._tcp.local"), anyInt())).thenReturn(result);
        mLatch = new CountDownLatch(1);
        mDiscoveryListener.onServiceFound(serviceInfo);
        mLatch.await();
        Map<String, MDNSDiscover.Result> expectedMap = new HashMap<>();
        expectedMap.put("device-1234._example._tcp.local", result);
        verify(mMockListener).onServicesChanged(eq(expectedMap));
        mLatch = new CountDownLatch(1);
        mDiscoveryListener.onServiceLost(serviceInfo);
        mLatch.await();
        expectedMap.clear();
        verify(mMockListener).onServicesChanged(eq(expectedMap));
        stopDiscoveryOnMainThread();
    }

    public void testServiceFoundFailedResolve() throws IOException, InterruptedException {
        startDiscoveryOnMainThread();
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        NsdServiceInfo serviceInfo = newNsdServiceInfo("device-1234", "_example._tcp.");
        when(mMockResolver.resolve(eq("device-1234._example._tcp.local"), anyInt())).thenThrow(new IOException());
        mDiscoveryListener.onServiceFound(serviceInfo);
        Thread.sleep(100);
        verify(mMockResolver).resolve(eq("device-1234._example._tcp.local"), anyInt());
        verify(mMockListener, never()).onServicesChanged(anyMap());
        stopDiscoveryOnMainThread();
    }

    public void testNoCallbackAfterStop() throws IOException, InterruptedException {
        startDiscoveryOnMainThread();
        mDiscoveryListener.onDiscoveryStarted(SERVICE_TYPE);
        NsdServiceInfo serviceInfo = newNsdServiceInfo("device-1234", "_example._tcp.");
        stopDiscoveryOnMainThread();
        mDiscoveryListener.onServiceFound(serviceInfo);
        mDiscoveryListener.onDiscoveryStopped(SERVICE_TYPE);
        Thread.sleep(100);
        verify(mMockListener, never()).onServicesChanged(anyMap());
    }

    private final Runnable DISCOVER_START = new Runnable() {
        @Override
        public void run() {
            mDiscoverResolver.start();
        }
    };

    private final Runnable DISCOVER_STOP = new Runnable() {
        @Override
        public void run() {
            mDiscoverResolver.stop();
        }
    };

    private void startDiscoveryOnMainThread() {
        runOnMainThread(DISCOVER_START);
    }

    private void stopDiscoveryOnMainThread() {
        runOnMainThread(DISCOVER_STOP);
    }

    /**
     * Runs the {@code action} on the main thread, and blocks this thread until the action has
     * completed. If the action throws any exception, it is caught and thrown again in this thread
     * as an unchecked exception.
     * @param action code to execute on the main thread
     * @throws RuntimeException wrapping any {@link Throwable} if thrown by the {@code action}
     */
    private void runOnMainThread(final Runnable action) {
        final Object condVar = new Object();
        final Throwable[] throwable = { null };
        synchronized (condVar) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        action.run();
                    } catch (Throwable t) {
                        throwable[0] = t;
                    }
                    synchronized (condVar) {
                        condVar.notify();
                    }
                }
            });
            try {
                condVar.wait();
                if (throwable[0] != null) {
                    throw new RuntimeException(throwable[0]);
                }
            } catch (InterruptedException e) {
                throw new Error(e);
            }
        }
    }

    private static NsdServiceInfo newNsdServiceInfo(String name, String type) {
        NsdServiceInfo result = new NsdServiceInfo();
        result.setServiceName(name);
        result.setServiceType(type);
        return result;
    }
}
