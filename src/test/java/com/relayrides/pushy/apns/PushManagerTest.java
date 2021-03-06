/* Copyright (c) 2013 RelayRides
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.relayrides.pushy.apns;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import io.netty.channel.nio.NioEventLoopGroup;

import java.security.KeyStore;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.relayrides.pushy.apns.util.SimpleApnsPushNotification;

public class PushManagerTest extends BasePushyTest {

	private class TestListener implements RejectedNotificationListener<SimpleApnsPushNotification> {

		private final AtomicInteger rejectedNotificationCount = new AtomicInteger(0);

		public void handleRejectedNotification(final SimpleApnsPushNotification notification, final RejectedNotificationReason reason) {
			this.rejectedNotificationCount.incrementAndGet();
		}

		public int getRejectedNotificationCount() {
			return this.rejectedNotificationCount.get();
		}
	}

	@Test
	public void testRegisterRejectedNotificationListener() throws InterruptedException {
		final SimpleApnsPushNotification notification = this.createTestNotification();

		final TestListener listener = new TestListener();
		this.getPushManager().registerRejectedNotificationListener(listener);

		assertEquals(0, listener.getRejectedNotificationCount());

		final int iterations = 100;
		this.getServer().failWithErrorAfterNotifications(RejectedNotificationReason.INVALID_TOKEN, 10);
		final CountDownLatch latch = this.getServer().getCountDownLatch(iterations);

		for (int i = 0; i < iterations; i++) {
			this.getPushManager().getQueue().put(notification);
		}

		this.waitForLatch(latch);

		assertEquals(1, listener.getRejectedNotificationCount());
	}

	@Test
	public void testUnregisterRejectedNotificationListener() {
		final TestListener listener = new TestListener();

		this.getPushManager().registerRejectedNotificationListener(listener);

		assertTrue(this.getPushManager().unregisterRejectedNotificationListener(listener));
		assertFalse(this.getPushManager().unregisterRejectedNotificationListener(listener));
	}

	@Test
	public void testShutdown() throws InterruptedException {
		{
			final PushManager<ApnsPushNotification> defaultGroupPushManager =
					new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

			defaultGroupPushManager.start();
			defaultGroupPushManager.shutdown();

			assertTrue(defaultGroupPushManager.getWorkerGroup().isShutdown());
		}

		{
			final NioEventLoopGroup group = new NioEventLoopGroup(1);

			final PushManager<ApnsPushNotification> providedGroupPushManager =
					new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, group, null);

			providedGroupPushManager.start();
			providedGroupPushManager.shutdown();

			assertEquals(group, providedGroupPushManager.getWorkerGroup());
			assertFalse(group.isShutdown());

			group.shutdownGracefully();
		}
	}

	@Test(expected = IllegalStateException.class)
	public void testDoubleStart() {
		final PushManager<ApnsPushNotification> doubleStartPushManager =
				new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

		doubleStartPushManager.start();
		doubleStartPushManager.start();
	}

	@Test(expected = IllegalStateException.class)
	public void testPrematureShutdown() throws InterruptedException {
		final PushManager<ApnsPushNotification> prematureShutdownPushManager =
				new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

		prematureShutdownPushManager.shutdown();
	}

	@Test
	public void testRepeatedShutdown() throws InterruptedException {
		final PushManager<ApnsPushNotification> repeatedShutdownPushManager =
				new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

		repeatedShutdownPushManager.start();
		repeatedShutdownPushManager.shutdown();
		repeatedShutdownPushManager.shutdown();
	}

	@Test
	public void testGetExpiredTokens() throws InterruptedException {
		assertTrue(this.getPushManager().getExpiredTokens().isEmpty());
	}

	@Test(expected = IllegalStateException.class)
	public void testGetExpiredTokensBeforeStart() throws InterruptedException {
		final PushManager<ApnsPushNotification> unstartedPushManager =
				new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

		unstartedPushManager.getExpiredTokens();
	}

	@Test(expected = IllegalStateException.class)
	public void testGetExpiredTokensAfterShutdown() throws InterruptedException {
		final PushManager<ApnsPushNotification> shutDownPushManager =
				new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

		shutDownPushManager.start();
		shutDownPushManager.shutdown();

		shutDownPushManager.getExpiredTokens();
	}

	@Test
	public void testIsStarted() throws InterruptedException {
		final PushManager<ApnsPushNotification> testPushManager =
				new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

		assertFalse(testPushManager.isStarted());

		testPushManager.start();
		assertTrue(testPushManager.isStarted());

		testPushManager.shutdown();
		assertFalse(testPushManager.isStarted());
	}

	@Test
	public void testIsShutDown() throws InterruptedException {
		final PushManager<ApnsPushNotification> testPushManager =
				new PushManager<ApnsPushNotification>(TEST_ENVIRONMENT, null, null, 1, null, null);

		assertFalse(testPushManager.isShutDown());

		testPushManager.start();
		assertFalse(testPushManager.isShutDown());

		testPushManager.shutdown();
		assertTrue(testPushManager.isShutDown());
	}

	@Test
	public void testHandleThreadDeath() throws InterruptedException {

		final class SelfDestructingApnsClientThread<T extends ApnsPushNotification> extends ApnsClientThread<T> {

			public SelfDestructingApnsClientThread(final PushManager<T> pushManager) {
				super(pushManager);
			}

			@Override
			public void run() {
				throw new RuntimeException("Self-destructing.");
			}
		}

		final class NotifyOnReplacementPushManager<T extends ApnsPushNotification> extends PushManager<T> {

			private final Object mutex;
			private volatile boolean replacedThread = false;

			protected NotifyOnReplacementPushManager(final ApnsEnvironment environment, final KeyStore keyStore,
					final char[] keyStorePassword, final int concurrentConnectionCount,
					final NioEventLoopGroup workerGroup, final BlockingQueue<T> queue, final Object mutex) {

				super(environment, keyStore, keyStorePassword, concurrentConnectionCount, workerGroup, queue);

				this.mutex = mutex;
			}

			@Override
			protected SelfDestructingApnsClientThread<T> createClientThread() {
				return new SelfDestructingApnsClientThread<T>(this);
			}

			@Override
			protected synchronized void replaceThread(final Thread t) {
				this.replacedThread = true;

				synchronized (this.mutex) {
					this.mutex.notifyAll();
				}
			}

			public boolean didReplaceThread() {
				return this.replacedThread;
			}
		}

		final Object mutex = new Object();
		final NotifyOnReplacementPushManager<ApnsPushNotification> testManager =
				new NotifyOnReplacementPushManager<ApnsPushNotification>(
						TEST_ENVIRONMENT, null, null, 1, null, null, mutex);

		synchronized (mutex) {
			testManager.start();
			mutex.wait(1000);
		}

		testManager.shutdown();
		assertTrue(testManager.didReplaceThread());
	}
}
