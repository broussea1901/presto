/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spi.block;

import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;
import io.airlift.slice.Slice;
import it.unimi.dsi.fastutil.Hash.Strategy;
import it.unimi.dsi.fastutil.objects.Object2LongOpenCustomHashMap;
import org.testng.annotations.Test;

import java.util.function.BiConsumer;

import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.TinyintType.TINYINT;
import static com.facebook.presto.spi.type.TypeUtils.writeNativeValue;
import static com.facebook.presto.spi.type.VarcharType.VARCHAR;
import static io.airlift.slice.Slices.utf8Slice;
import static org.testng.Assert.assertEquals;

public class TestBlockRetainedSizeBreakdown
{
    private static final int EXPECTED_ENTRIES = 100;

    private int objectSize;
    private final Object2LongOpenCustomHashMap<Object> trackedObjects = new Object2LongOpenCustomHashMap<>(new ObjectStrategy());

    private final BiConsumer<Object, Long> consumer = (object, size) -> {
        objectSize += size;
        trackedObjects.addTo(object, 1);
    };

    @Test
    public void testArrayBlock()
    {
        BlockBuilder arrayBlockBuilder = new ArrayBlockBuilder(BIGINT, new BlockBuilderStatus(), EXPECTED_ENTRIES);
        for (int i = 0; i < EXPECTED_ENTRIES; i++) {
            BlockBuilder arrayElementBuilder = arrayBlockBuilder.beginBlockEntry();
            writeNativeValue(BIGINT, arrayElementBuilder, castIntegerToObect(i, BIGINT));
            arrayBlockBuilder.closeEntry();
        }
        checkRetainedSize(arrayBlockBuilder.build(), false);
    }

    @Test
    public void testByteArrayBlock()
    {
        BlockBuilder blockBuilder = new ByteArrayBlockBuilder(new BlockBuilderStatus(), EXPECTED_ENTRIES);
        for (int i = 0; i < EXPECTED_ENTRIES; i++) {
            blockBuilder.writeByte(i);
        }
        checkRetainedSize(blockBuilder.build(), false);
    }

    @Test
    public void testDictionaryBlock()
    {
        Block keyDictionaryBlock = createSliceArrayBlock(EXPECTED_ENTRIES);
        int[] keyIds = new int[EXPECTED_ENTRIES];
        for (int i = 0; i < keyIds.length; i++) {
            keyIds[i] = i;
        }
        checkRetainedSize(new DictionaryBlock(EXPECTED_ENTRIES, keyDictionaryBlock, keyIds), false);
    }

    @Test
    public void testFixedWidthBlock()
    {
        BlockBuilder blockBuilder = new FixedWidthBlockBuilder(8, new BlockBuilderStatus(), EXPECTED_ENTRIES);
        writeEntries(EXPECTED_ENTRIES, blockBuilder, DOUBLE);
        checkRetainedSize(blockBuilder.build(), true);
    }

    @Test
    public void testIntArrayBlock()
    {
        BlockBuilder blockBuilder = new IntArrayBlockBuilder(new BlockBuilderStatus(), EXPECTED_ENTRIES);
        writeEntries(EXPECTED_ENTRIES, blockBuilder, INTEGER);
        checkRetainedSize(blockBuilder.build(), false);
    }

    @Test
    public void testInterleavedBlock()
    {
        BlockBuilder blockBuilder = new InterleavedBlockBuilder(ImmutableList.of(INTEGER, INTEGER), new BlockBuilderStatus(), EXPECTED_ENTRIES);
        writeEntries(EXPECTED_ENTRIES, blockBuilder, INTEGER);
        checkRetainedSize(blockBuilder.build(), false);
    }

    @Test
    public void testLongArrayBlock()
    {
        BlockBuilder blockBuilder = new LongArrayBlockBuilder(new BlockBuilderStatus(), EXPECTED_ENTRIES);
        writeEntries(EXPECTED_ENTRIES, blockBuilder, BIGINT);
        checkRetainedSize(blockBuilder.build(), false);
    }

    @Test
    public void testRunLengthEncodedBlock()
    {
        BlockBuilder blockBuilder = new LongArrayBlockBuilder(new BlockBuilderStatus(), 1);
        writeEntries(1, blockBuilder, BIGINT);
        checkRetainedSize(new RunLengthEncodedBlock(blockBuilder.build(), 1), false);
    }

    @Test
    public void testShortArrayBlock()
    {
        BlockBuilder blockBuilder = new ShortArrayBlockBuilder(new BlockBuilderStatus(), EXPECTED_ENTRIES);
        for (int i = 0; i < EXPECTED_ENTRIES; i++) {
            blockBuilder.writeShort(i);
        }
        checkRetainedSize(blockBuilder.build(), false);
    }

    @Test
    public void testSliceArrayBlock()
    {
        checkRetainedSize(createSliceArrayBlock(EXPECTED_ENTRIES), true);
    }

    @Test
    public void testVariableWidthBlock()
    {
        BlockBuilder blockBuilder = new VariableWidthBlockBuilder(new BlockBuilderStatus(), EXPECTED_ENTRIES, 4);
        writeEntries(EXPECTED_ENTRIES, blockBuilder, VARCHAR);
        checkRetainedSize(blockBuilder.build(), false);
    }

    private static final class ObjectStrategy
            implements Strategy<Object>
    {
        @Override
        public int hashCode(Object object)
        {
            return System.identityHashCode(object);
        }

        @Override
        public boolean equals(Object left, Object right)
        {
            return left == right;
        }
    }

    private void checkRetainedSize(Block block, boolean getRegionCreateNewObjects)
    {
        objectSize = 0;
        trackedObjects.clear();

        block.retainedBytesForEachPart(consumer);
        assertEquals(objectSize, block.getRetainedSizeInBytes());

        Block copyBlock = block.getRegion(0, block.getPositionCount() / 2);
        copyBlock.retainedBytesForEachPart(consumer);
        assertEquals(objectSize, block.getRetainedSizeInBytes() + copyBlock.getRetainedSizeInBytes());

        assertEquals(trackedObjects.getLong(block), 1);
        assertEquals(trackedObjects.getLong(copyBlock), 1);
        trackedObjects.remove(block);
        trackedObjects.remove(copyBlock);
        for (long value : trackedObjects.values()) {
            assertEquals(value, getRegionCreateNewObjects ? 1 : 2);
        }
    }

    private static void writeEntries(int expectedEntries, BlockBuilder blockBuilder, Type type)
    {
        for (int i = 0; i < expectedEntries; i++) {
            writeNativeValue(type, blockBuilder, castIntegerToObect(i, type));
        }
    }

    private static Object castIntegerToObect(int value, Type type)
    {
        if (type == INTEGER || type == TINYINT || type == BIGINT) {
            return (long) value;
        }
        if (type == VARCHAR) {
            return String.valueOf(value);
        }
        if (type == DOUBLE) {
            return (double) value;
        }
        throw new UnsupportedOperationException();
    }

    private static Block createSliceArrayBlock(int entries)
    {
        Slice[] sliceArray = new Slice[entries];
        for (int i = 0; i < entries; i++) {
            sliceArray[i] = utf8Slice(i + "");
        }
        return new SliceArrayBlock(sliceArray.length, sliceArray);
    }
}
