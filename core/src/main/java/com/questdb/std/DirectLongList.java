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
 * As a special exception, the copyright holders give permission to link the
 * code of portions of this program with the OpenSSL library under certain
 * conditions as described in each individual source file and distribute
 * linked combinations including the program with the OpenSSL library. You
 * must comply with the GNU Affero General Public License in all respects for
 * all of the code used other than as permitted herein. If you modify file(s)
 * with this exception, you may extend this exception to your version of the
 * file(s), but you are not obligated to do so. If you do not wish to do so,
 * delete this exception statement from your version. If you delete this
 * exception statement from all source files in the program, then also delete
 * it in the license file.
 *
 ******************************************************************************/

package com.questdb.std;

import com.questdb.misc.Unsafe;

public class DirectLongList extends DirectMemoryStructure implements Mutable {

    public static final int CACHE_LINE_SIZE = 64;
    private final int pow2;
    private final int onePow2;
    long pos;
    long start;
    long limit;

    public DirectLongList(long capacity) {
        this.pow2 = 3;
        this.address = Unsafe.getUnsafe().allocateMemory((capacity << 3) + CACHE_LINE_SIZE);
        this.start = this.pos = address + (address & (CACHE_LINE_SIZE - 1));
        this.limit = pos + ((capacity - 1) << 3);
        this.onePow2 = (1 << 3);
    }

    public void add(long x) {
        ensureCapacity();
        Unsafe.getUnsafe().putLong(pos, x);
        pos += 8;
    }

    public final void add(DirectLongList that) {
        int count = (int) (that.pos - that.start);
        if (limit - pos < count) {
            extend((int) (this.limit - this.start + count) >> 1);
        }
        Unsafe.getUnsafe().copyMemory(that.start, this.pos, count);
        this.pos += count;
    }

    public int binarySearch(long v) {
        int low = 0;
        int high = (int) ((pos - start) >> 3) - 1;

        while (low <= high) {

            if (high - low < 65) {
                return scanSearch(v);
            }

            int mid = (low + high) >>> 1;
            long midVal = Unsafe.getUnsafe().getLong(start + (mid << 3));

            if (midVal < v)
                low = mid + 1;
            else if (midVal > v)
                high = mid - 1;
            else
                return mid;
        }
        return -(low + 1);
    }

    public void clear() {
        clear(0);
    }

    public void clear(long b) {
        pos = start;
        zero(b);
    }

    public long get(long p) {
        return Unsafe.getUnsafe().getLong(start + (p << 3));
    }

    public int scanSearch(long v) {
        int sz = size();
        for (int i = 0; i < sz; i++) {
            long f = get(i);
            if (f == v) {
                return i;
            }
            if (f > v) {
                return -(i + 1);
            }
        }
        return -(sz + 1);
    }

    public void set(long p, long v) {
        assert p >= 0 && p <= (limit - start) >> 3;
        Unsafe.getUnsafe().putLong(start + (p << 3), v);
    }

    public void setCapacity(long capacity) {
        if (capacity << pow2 > limit - start) {
            extend(capacity);
        }
    }

    public void setPos(long p) {
        pos = start + (p << pow2);
    }

    public int size() {
        return (int) ((pos - start) >> pow2);
    }

    public DirectLongList subset(int lo, int hi) {
        DirectLongList that = new DirectLongList(hi - lo);
        Unsafe.getUnsafe().copyMemory(start + (lo << 3), that.start, (hi - lo) << 3);
        that.pos += (hi - lo) << 3;
        return that;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        for (int i = 0; i < size(); i++) {
            if (i > 0) {
                sb.append(',').append(' ');
            }
            sb.append(get(i));
        }
        sb.append('}');
        return sb.toString();
    }

    public void zero(long v) {
        Unsafe.getUnsafe().setMemory(start, limit - start + onePow2, (byte) v);
    }

    void ensureCapacity() {
        if (this.pos > limit) {
            extend((int) ((limit - start + onePow2) >> (pow2 - 1)));
        }
    }

    private void extend(long capacity) {
        long address = Unsafe.getUnsafe().allocateMemory((capacity << pow2) + CACHE_LINE_SIZE);
        long start = address + (address & (CACHE_LINE_SIZE - 1));
        Unsafe.getUnsafe().copyMemory(this.start, start, limit + onePow2 - this.start);
        if (this.address != 0) {
            Unsafe.getUnsafe().freeMemory(this.address);
        }
        this.pos = this.pos - this.start + start;
        this.limit = start + ((capacity - 1) << pow2);
        this.address = address;
        this.start = start;
    }
}