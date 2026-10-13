package com.bgsoftware.superiorskyblock.pvc.runtime;

import com.bgsoftware.superiorskyblock.world.chunk.NativeRegionFiles;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class NativeRegionFilesTest {
    @Test public void acceptsConcreteAndInterfaceReturnDescriptorsWithoutCreatingMissingFiles() throws Exception {
        TestRegion region = new TestRegion();
        for (Object cache : new Object[] { new ConcreteCache(region), new InterfaceCache(region) }) {
            assertSame(region, NativeRegionFiles.existing(cache, 7, 9));
            assertSame(region, NativeRegionFiles.loaded(cache, 7, 9));
            assertTrue(NativeRegionFiles.hasChunk(region, new TestChunkPosition(7, 9)));
            assertFalse(NativeRegionFiles.hasChunk(region, new TestChunkPosition(8, 9)));
            assertNull(NativeRegionFiles.existing(cache, 99, 99));
            assertNull(NativeRegionFiles.loaded(cache, 99, 99));
            NativeRegionFiles.flush(region);
        }
        assertEquals(2, region.flushes);
    }

    @Test public void preservesNativeFsyncFailureAsFailure() throws Exception {
        TestRegion region = new TestRegion();
        IOException failure = new IOException("Synthetic fsync failure");
        region.failure = failure;
        try { NativeRegionFiles.flush(region); fail("Failed flush must not acknowledge a write"); }
        catch (IOException actual) { assertSame(failure, actual); }
        assertEquals(0, region.flushes);
    }

    @Test public void preservesCacheIoFailureAndRejectsMissingFlushContract() throws Exception {
        IOException failure = new IOException("Synthetic region cache IO failure");
        try { NativeRegionFiles.existing(new FailingCache(failure), 7, 9); fail("Cache IO failure must propagate"); }
        catch (IOException actual) { assertSame(failure, actual); }
        try { NativeRegionFiles.flush(new MissingFlush()); fail("An unsupported file cannot produce a durable acknowledgement"); }
        catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Unsupported native region-file ABI")); }
    }

    public interface TestRegionInterface {
        boolean hasChunk(TestChunkPosition position);
        void flush() throws IOException;
    }
    public static final class TestChunkPosition {
        final int x, z;
        TestChunkPosition(int x, int z) { this.x = x; this.z = z; }
    }
    public static final class TestRegion implements TestRegionInterface {
        int flushes;
        IOException failure;
        public boolean hasChunk(TestChunkPosition position) { return position.x == 7 && position.z == 9; }
        public void flush() throws IOException {
            if (failure != null) throw failure;
            flushes++;
        }
    }
    public static final class ConcreteCache {
        final TestRegion region;
        ConcreteCache(TestRegion region) { this.region = region; }
        public TestRegion moonrise$getRegionFileIfExists(int x, int z) { return x == 7 && z == 9 ? region : null; }
        public TestRegion moonrise$getRegionFileIfLoaded(int x, int z) { return moonrise$getRegionFileIfExists(x, z); }
    }
    public static final class InterfaceCache {
        final TestRegionInterface region;
        InterfaceCache(TestRegionInterface region) { this.region = region; }
        public TestRegionInterface moonrise$getRegionFileIfExists(int x, int z) { return x == 7 && z == 9 ? region : null; }
        public TestRegionInterface moonrise$getRegionFileIfLoaded(int x, int z) { return moonrise$getRegionFileIfExists(x, z); }
    }
    public static final class FailingCache {
        final IOException failure;
        FailingCache(IOException failure) { this.failure = failure; }
        public TestRegion moonrise$getRegionFileIfExists(int x, int z) throws IOException { throw failure; }
        public TestRegion moonrise$getRegionFileIfLoaded(int x, int z) { return null; }
    }
    public static final class MissingFlush {
        public boolean hasChunk(TestChunkPosition position) { return true; }
    }
}
