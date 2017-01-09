/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2016 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.factory;

import com.questdb.Journal;
import com.questdb.ex.FactoryFullException;
import com.questdb.ex.JournalException;
import com.questdb.ex.JournalLockedException;
import com.questdb.ex.RetryLockException;
import com.questdb.factory.configuration.JournalMetadata;
import com.questdb.factory.configuration.JournalStructure;
import com.questdb.misc.Rnd;
import com.questdb.std.LongList;
import com.questdb.std.ObjList;
import com.questdb.test.tools.AbstractTest;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class CachingReaderFactory2Test extends AbstractTest {

    @Test
    public void testCloseWithActiveReader() throws Exception {
        // create journal
        final JournalMetadata<?> m = new JournalStructure("x").$date("ts").$().build();
        getWriterFactory().writer(m).close();

        try (final CachingReaderFactory2 rf = new CachingReaderFactory2(theFactory.getConfiguration(), 2)) {
            Journal reader = rf.reader(m);
            Assert.assertNotNull(reader);
            rf.close();
            Assert.assertTrue(reader.isOpen());
            reader.close();
            Assert.assertFalse(reader.isOpen());
        }
    }

    @Test
    public void testCloseWithInactiveReader() throws Exception {
        // create journal
        final JournalMetadata<?> m = new JournalStructure("x").$date("ts").$().build();
        getWriterFactory().writer(m).close();

        try (final CachingReaderFactory2 rf = new CachingReaderFactory2(theFactory.getConfiguration(), 2)) {
            Journal reader = rf.reader(m);
            Assert.assertNotNull(reader);
            reader.close();
            Assert.assertTrue(reader.isOpen());
            rf.close();
            Assert.assertFalse(reader.isOpen());
        }
    }

    @Test
    public void testConcurrentOpenAndClose() throws Exception {

        final int readerCount = 5;
        int threadCount = 2;
        final int iterations = 1000;

        // create journals to read
        final JournalMetadata<?>[] meta = new JournalMetadata[readerCount];
        for (int i = 0; i < readerCount; i++) {
            final JournalMetadata<?> m = new JournalStructure("x" + i).$date("ts").$().build();
            getWriterFactory().writer(m).close();
            meta[i] = m;
        }

        try (final CachingReaderFactory2 rf = new CachingReaderFactory2(theFactory.getConfiguration(), 2)) {

            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final CountDownLatch halt = new CountDownLatch(threadCount);
            final AtomicInteger errors = new AtomicInteger();

            for (int i = 0; i < threadCount; i++) {
                final int x = i;
                new Thread() {
                    @Override
                    public void run() {
                        Rnd rnd = new Rnd(x, -x);
                        try {
                            barrier.await();

                            for (int i = 0; i < iterations; i++) {
                                JournalMetadata<?> m = meta[rnd.nextPositiveInt() % readerCount];

                                try (Journal ignored = rf.reader(m)) {
                                    LockSupport.parkNanos(100);
                                }
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                            errors.incrementAndGet();
                        } finally {
                            halt.countDown();
                        }
                    }
                }.start();
            }

            halt.await();
            Assert.assertEquals(0, errors.get());
        }
    }

    @SuppressWarnings("InfiniteLoopStatement")
    @Test
    public void testGetReadersBeforeFailure() throws Exception {
        // create journal
        final JournalMetadata<?> m = new JournalStructure("x").$date("ts").$().build();
        getWriterFactory().writer(m).close();

        try (final CachingReaderFactory2 rf = new CachingReaderFactory2(theFactory.getConfiguration(), 2)) {

            ObjList<Journal> readers = new ObjList<>();
            try {
                do {
                    readers.add(rf.reader(m));
                } while (true);
            } catch (FactoryFullException e) {
                Assert.assertEquals(rf.getMaxEntries(), readers.size());
            } finally {
                for (int i = 0, n = readers.size(); i < n; i++) {
                    readers.getQuick(i).close();
                }
            }
        }
    }

    @Test
    public void testLockBusyReader() throws Exception {
        final int readerCount = 5;
        int threadCount = 2;
        final int iterations = 10000;

        // create journals to read
        final JournalMetadata<?>[] meta = new JournalMetadata[readerCount];
        for (int i = 0; i < readerCount; i++) {
            final JournalMetadata<?> m = new JournalStructure("x" + i).$date("ts").$().build();
            getWriterFactory().writer(m).close();
            meta[i] = m;
        }

        try (final CachingReaderFactory2 rf = new CachingReaderFactory2(theFactory.getConfiguration(), 2)) {

            final CyclicBarrier barrier = new CyclicBarrier(threadCount);
            final CountDownLatch halt = new CountDownLatch(threadCount);
            final AtomicInteger errors = new AtomicInteger();
            final LongList lockTimes = new LongList();
            final LongList workerTimes = new LongList();

            new Thread() {
                @Override
                public void run() {
                    Rnd rnd = new Rnd();
                    try {
                        barrier.await();
                        for (int i = 0; i < iterations; i++) {
                            String name = meta[rnd.nextPositiveInt() % readerCount].getName();
                            while (true) {
                                try {
                                    rf.lock(name);
                                    lockTimes.add(System.currentTimeMillis());
                                    LockSupport.parkNanos(1000L);
                                    rf.unlock(name);
                                    break;
                                } catch (JournalException e) {
                                    if (!(e instanceof RetryLockException)) {
                                        e.printStackTrace();
                                        errors.incrementAndGet();
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        errors.incrementAndGet();
                    }
                    halt.countDown();
                }
            }.start();

            new Thread() {
                @Override
                public void run() {
                    Rnd rnd = new Rnd();

                    workerTimes.add(System.currentTimeMillis());
                    for (int i = 0; i < iterations; i++) {
                        JournalMetadata<?> metadata = meta[rnd.nextPositiveInt() % readerCount];
                        try (Journal<?> ignored = rf.reader(metadata)) {
                            if (metadata == meta[readerCount - 1] && barrier.getNumberWaiting() > 0) {
                                barrier.await();
                            }
                            LockSupport.parkNanos(100L);
                        } catch (JournalLockedException ignored) {
                        } catch (Exception e) {
                            e.printStackTrace();
                            errors.incrementAndGet();
                        }
                    }
                    workerTimes.add(System.currentTimeMillis());

                    halt.countDown();
                }
            }.start();

            halt.await();
            Assert.assertEquals(0, errors.get());

            // check that there are lock times between worker times
            int count = 0;

            // ensure that we have worker times
            Assert.assertEquals(2, workerTimes.size());
            long lo = workerTimes.get(0);
            long hi = workerTimes.get(1);

            Assert.assertTrue(lockTimes.size() > 0);

            for (int i = 0, n = lockTimes.size(); i < n; i++) {
                long t = lockTimes.getQuick(i);
                if (t > lo && t < hi) {
                    count++;
                }
            }

            Assert.assertTrue(count > 0);
        }

    }

    @Test
    public void testLockUnlock() throws Exception {
        // create journals
        final JournalMetadata<?> m1 = new JournalStructure("x").$date("ts").$().build();
        getWriterFactory().writer(m1).close();

        final JournalMetadata<?> m2 = new JournalStructure("y").$date("ts").$().build();
        getWriterFactory().writer(m2).close();


        Journal x, y;
        try (final CachingReaderFactory2 rf = new CachingReaderFactory2(theFactory.getConfiguration(), 2)) {
            x = rf.reader(m1);
            Assert.assertNotNull(x);

            y = rf.reader(m2);
            Assert.assertNotNull(y);

            // expect lock to fail because we have "x" open
            try {
                rf.lock(m1.getName());
                Assert.fail();
            } catch (RetryLockException ignore) {
            }

            x.close();

            // expect lock to succeed after we closed "x"
            rf.lock(m1.getName());

            // expect "x" to be physically closed
            Assert.assertFalse(x.isOpen());

            // "x" is locked, expect this to fail
            try {
                Assert.assertNull(rf.reader(m1));
            } catch (JournalLockedException ignored) {
            }

            rf.unlock(m1.getName());

            x = rf.reader(m1);
            Assert.assertNotNull(x);
            x.close();

            Assert.assertTrue(x.isOpen());
        }

        Assert.assertTrue(y.isOpen());
        y.close();

        Assert.assertFalse(y.isOpen());

        // "x" was not busy and should be closed by factory
        Assert.assertFalse(x.isOpen());
    }

    @Test
    public void testSerialOpenClose() throws Exception {
        // create journal
        final JournalMetadata<?> m = new JournalStructure("x").$date("ts").$().build();
        getWriterFactory().writer(m).close();

        try (final CachingReaderFactory2 rf = new CachingReaderFactory2(theFactory.getConfiguration(), 2)) {
            Journal firstReader = null;
            for (int i = 0; i < 1000; i++) {
                try (Journal reader = rf.reader(m)) {
                    if (firstReader == null) {
                        firstReader = reader;
                    }
                    Assert.assertNotNull(reader);
                    Assert.assertSame(firstReader, reader);
                }
            }
        }
    }
}