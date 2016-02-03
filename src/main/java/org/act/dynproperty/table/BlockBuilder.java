/*
 * Copyright (C) 2011 the original author or authors.
 * See the notice.md file distributed with this work for additional
 * information regarding copyright ownership.
 *
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
package org.act.dynproperty.table;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;

import java.util.Comparator;

import org.act.dynproperty.util.DynamicSliceOutput;
import org.act.dynproperty.util.IntVector;
import org.act.dynproperty.util.Slice;
import org.act.dynproperty.util.VariableLengthQuantity;

import static org.act.dynproperty.util.SizeOf.SIZE_OF_INT;

public class BlockBuilder
{
    private final int blockRestartInterval;
    private final IntVector restartPositions;
    private final Comparator<Slice> comparator;

    private int entryCount;
    private int restartBlockEntryCount;

    private boolean finished;
    private final DynamicSliceOutput block;
    private Slice lastKey;

    public BlockBuilder(int estimatedSize, int blockRestartInterval, Comparator<Slice> comparator)
    {
        Preconditions.checkArgument(estimatedSize >= 0, "estimatedSize is negative");
        Preconditions.checkArgument(blockRestartInterval >= 0, "blockRestartInterval is negative");
        Preconditions.checkNotNull(comparator, "comparator is null");

        this.block = new DynamicSliceOutput(estimatedSize);
        this.blockRestartInterval = blockRestartInterval;
        this.comparator = comparator;

        restartPositions = new IntVector(32);
        restartPositions.add(0);  // first restart point must be 0
    }

    public void reset()
    {
        block.reset();
        entryCount = 0;
        restartPositions.clear();
        restartPositions.add(0); // first restart point must be 0
        restartBlockEntryCount = 0;
        lastKey = null;
        finished = false;
    }

    public int getEntryCount()
    {
        return entryCount;
    }

    public boolean isEmpty()
    {
        return entryCount == 0;
    }

    public int currentSizeEstimate()
    {
        // no need to estimate if closed
        if (finished) {
            return block.size();
        }

        // no records is just a single int
        if (block.size() == 0) {
            return SIZE_OF_INT;
        }

        return block.size() +                              // raw data buffer
                restartPositions.size() * SIZE_OF_INT +    // restart positions
                SIZE_OF_INT +                              // restart position size
                SIZE_OF_INT;                               // data size
    }

    public void add(BlockEntry blockEntry)
    {
        Preconditions.checkNotNull(blockEntry, "blockEntry is null");
        add(blockEntry.getKey(), blockEntry.getValue());
    }

    public void add(Slice key, Slice value)
    {
        Preconditions.checkNotNull(key, "key is null");
        Preconditions.checkNotNull(value, "value is null");
        Preconditions.checkState(!finished, "block is finished");
        Preconditions.checkPositionIndex(restartBlockEntryCount, blockRestartInterval);

        Preconditions.checkArgument(lastKey == null || comparator.compare(key, lastKey) >= 0, "key %s must be greater than last key %s",key,lastKey);

        int sharedKeyBytes = 0;
        if (restartBlockEntryCount < blockRestartInterval) {
            sharedKeyBytes = calculateSharedBytes(key, lastKey);
        }
        else {
            // restart prefix compression
            restartPositions.add(block.size());
            restartBlockEntryCount = 0;
        }

        //stop using the sharedkey, make easy for update the record
        sharedKeyBytes = 0;
        
        int nonSharedKeyBytes = key.length() - sharedKeyBytes;

        // write "<shared><non_shared><value_size>"
        VariableLengthQuantity.writeVariableLengthInt(sharedKeyBytes, block);
        VariableLengthQuantity.writeVariableLengthInt(nonSharedKeyBytes, block);
        VariableLengthQuantity.writeVariableLengthInt(value.length(), block);

        // write non-shared key bytes
        block.writeBytes(key, sharedKeyBytes, nonSharedKeyBytes);

        // write value bytes
        block.writeBytes(value, 0, value.length());

        // update last key
        lastKey = key;

        // update state
        entryCount++;
        restartBlockEntryCount++;
    }

    public static int calculateSharedBytes(Slice leftKey, Slice rightKey)
    {
        int sharedKeyBytes = 0;

        if (leftKey != null && rightKey != null) {
            int minSharedKeyBytes = Ints.min(leftKey.length(), rightKey.length());
            while (sharedKeyBytes < minSharedKeyBytes && leftKey.getByte(sharedKeyBytes) == rightKey.getByte(sharedKeyBytes)) {
                sharedKeyBytes++;
            }
        }

        return sharedKeyBytes;
    }

    public Slice finish()
    {
        if (!finished) {
            finished = true;

            if (entryCount > 0) {
                restartPositions.write(block);
                block.writeInt(restartPositions.size());
            }
            else {
                block.writeInt(0);
            }
        }
        return block.slice( block.size() );
    }
}
