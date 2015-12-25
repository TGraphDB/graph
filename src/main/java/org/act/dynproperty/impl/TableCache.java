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
package org.act.dynproperty.impl;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutionException;

import org.act.dynproperty.table.FileChannelTable;
import org.act.dynproperty.table.MMapTable;
import org.act.dynproperty.table.Table;
import org.act.dynproperty.table.UserComparator;
import org.act.dynproperty.util.Closeables;
import org.act.dynproperty.util.Finalizer;
import org.act.dynproperty.util.InternalTableIterator;
import org.act.dynproperty.util.Slice;

public class TableCache
{
    private final LoadingCache<Long, TableAndFile> cache;
    private final Finalizer<Table> finalizer = new Finalizer<>(1);;

    public TableCache(final File databaseDir, int tableCacheSize, final UserComparator userComparator, final boolean verifyChecksums, final boolean isStableFile)
    {
        Preconditions.checkNotNull(databaseDir, "databaseName is null");
        cache = CacheBuilder.newBuilder()
                .maximumSize(tableCacheSize)
                .removalListener(new RemovalListener<Long, TableAndFile>()
                {
                    @Override
                    public void onRemoval(RemovalNotification<Long, TableAndFile> notification)
                    {
                        Table table = notification.getValue().getTable();
                        finalizer.addCleanup(table, table.closer());
                    }
                })
                .build(new CacheLoader<Long, TableAndFile>()
                {
                    @Override
                    public TableAndFile load(Long fileNumber)
                            throws IOException
                    {
                        return new TableAndFile(databaseDir, fileNumber, userComparator, verifyChecksums, isStableFile);
                    }
                });
    }

    public InternalTableIterator newIterator(FileMetaData file)
    {
        return newIterator(file.getNumber());
    }

    public InternalTableIterator newIterator(long number)
    {
        return new InternalTableIterator(getTable(number).iterator());
    }

    public long getApproximateOffsetOf(FileMetaData file, Slice key)
    {
        return getTable(file.getNumber()).getApproximateOffsetOf(key);
    }

    private Table getTable(long number)
    {
        Table table;
        try {
            table = cache.get(number).getTable();
        }
        catch (ExecutionException e) {
            Throwable cause = e;
            if (e.getCause() != null) {
                cause = e.getCause();
            }
            throw new RuntimeException("Could not open table " + number, cause);
        }
        return table;
    }

    public void close()
    {
        cache.invalidateAll();
        finalizer.destroy();
    }

    public void evict(long number)
    {
        cache.invalidate(number);
    }

    private static final class TableAndFile
    {
        private final Table table;
        private final FileChannel fileChannel;

        private TableAndFile(File databaseDir, long fileNumber, UserComparator userComparator, boolean verifyChecksums, boolean isStableFile)
                throws IOException
        {
            String tableFileName;
            if( isStableFile )
                tableFileName = Filename.stableFileName(fileNumber);
            else
                tableFileName = Filename.unStableFileName(fileNumber);
            File tableFile = new File(databaseDir, tableFileName);
            fileChannel = new FileInputStream(tableFile).getChannel();
            try {
                //FIXME 
                if ( true ) {
                    table = new MMapTable(tableFile.getAbsolutePath(), fileChannel, userComparator, verifyChecksums);
                }
                else {
                    table = new FileChannelTable(tableFile.getAbsolutePath(), fileChannel, userComparator, verifyChecksums);
                }
            }
            catch (IOException e) {
                Closeables.closeQuietly(fileChannel);
                throw e;
            }
        }

        public Table getTable()
        {
            return table;
        }
    }
}
