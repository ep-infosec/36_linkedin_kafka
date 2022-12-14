/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.memory;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


public class WeakMemoryPoolTest {
    private static final int FORTY_MEGABYTES = 40 * 1024 * 1024;

    @Test
    public void testNegativeAllocation() {
        WeakMemoryPool memoryPool = new WeakMemoryPool();
        assertThrows(IllegalArgumentException.class, () -> memoryPool.tryAllocate(-1));
    }

    @Test
    public void testZeroAllocation() {
        WeakMemoryPool memoryPool = new WeakMemoryPool();
        memoryPool.tryAllocate(0);
    }

    @Test
    public void testNullRelease() {
        WeakMemoryPool memoryPool = new WeakMemoryPool();
        assertThrows(IllegalArgumentException.class, () -> memoryPool.release(null));
    }

    @Test
    public void testAllocationMemorySize() {
        WeakMemoryPool pool = new WeakMemoryPool();
        ByteBuffer buffer1 = pool.tryAllocate(FORTY_MEGABYTES + 1);
        ByteBuffer buffer2 = pool.tryAllocate(FORTY_MEGABYTES + 2);
        ByteBuffer buffer3 = pool.tryAllocate(FORTY_MEGABYTES + 3);

        pool.release(buffer1);
        ByteBuffer reuse1 = pool.tryAllocate(FORTY_MEGABYTES);
        // Compare the references
        assertTrue(reuse1 == buffer1);

        pool.release(buffer2);
        pool.release(buffer3);
        ByteBuffer reuse3 = pool.tryAllocate(FORTY_MEGABYTES + 3);
        ByteBuffer reuse2 = pool.tryAllocate(FORTY_MEGABYTES + 2);

        assertTrue(reuse3 == buffer3);
        assertTrue(reuse2 == buffer2);
    }

    @Test
    public void testAllocation() {
        WeakMemoryPool pool = new WeakMemoryPool();
        ByteBuffer buffer1 = pool.tryAllocate(FORTY_MEGABYTES + 1);
        ByteBuffer buffer2 = pool.tryAllocate(FORTY_MEGABYTES + 2);
        ByteBuffer buffer3 = pool.tryAllocate(FORTY_MEGABYTES + 3);

        pool.release(buffer1);
        ByteBuffer reuse1 = pool.tryAllocate(FORTY_MEGABYTES);
        // Compare the references
        assertEquals(System.identityHashCode(reuse1), System.identityHashCode(buffer1));

        pool.release(buffer2);
        pool.release(buffer3);
        ByteBuffer reuse3 = pool.tryAllocate(FORTY_MEGABYTES + 3);
        ByteBuffer reuse2 = pool.tryAllocate(FORTY_MEGABYTES + 2);

        assertEquals(System.identityHashCode(reuse3), System.identityHashCode(buffer3));
        assertEquals(System.identityHashCode(reuse2), System.identityHashCode(buffer2));
    }

    @Test
    public void testAllocationGC() {
        // Clean all garbage before we begin!
        System.gc();

        WeakMemoryPool pool = new WeakMemoryPool();

        ByteBuffer buffer1 = ByteBuffer.allocate(FORTY_MEGABYTES + 1);
        ByteBuffer buffer2 = ByteBuffer.allocate(FORTY_MEGABYTES + 5);
        ByteBuffer buffer3 = ByteBuffer.allocate(FORTY_MEGABYTES + 9);

        // The byte buffers are not reachable from gc-roots
        int identifier1 = System.identityHashCode(buffer1);
        int identifier2 = System.identityHashCode(buffer2);
        int identifier3 = System.identityHashCode(buffer3);

        pool.release(buffer1);
        pool.release(buffer2);
        pool.release(buffer3);

        assertEquals(identifier2, System.identityHashCode(pool.tryAllocate(FORTY_MEGABYTES + 3)));
        assertEquals(identifier3, System.identityHashCode(pool.tryAllocate(FORTY_MEGABYTES + 7)));
        assertEquals(identifier1, System.identityHashCode(pool.tryAllocate(FORTY_MEGABYTES + 0)));

        pool.release(buffer1);
        pool.release(buffer2);
        pool.release(buffer3);

        buffer1 = null;
        buffer2 = null;
        buffer3 = null;

        // Reclaim all the objects
        System.gc();

        // Assert that the object is a different one!
        assertNotEquals(identifier2, System.identityHashCode(pool.tryAllocate(FORTY_MEGABYTES + 3)));
        assertNotEquals(identifier3, System.identityHashCode(pool.tryAllocate(FORTY_MEGABYTES + 7)));
        assertNotEquals(identifier1, System.identityHashCode(pool.tryAllocate(FORTY_MEGABYTES + 0)));
    }
}
