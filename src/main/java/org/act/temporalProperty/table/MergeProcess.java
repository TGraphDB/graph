package org.act.temporalProperty.table;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.act.temporalProperty.impl.*;
import org.act.temporalProperty.meta.PropertyMetaData;
import org.act.temporalProperty.meta.SystemMeta;
import org.act.temporalProperty.util.MergingIterator;
import org.act.temporalProperty.util.Slice;
import org.act.temporalProperty.util.TableLatestValueIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件合并过程
 *
 */
public class MergeProcess extends Thread
{
    private final SystemMeta systemMeta;
    private final String storeDir;
    private volatile boolean mergeIsHappening = false;
    private Logger log = LoggerFactory.getLogger( MergeProcess.class );
    //等待写入磁盘的MemTable的队列
    private BlockingQueue<MemTable> mergeWaitingQueue = new LinkedBlockingQueue<MemTable>();

    public MergeProcess(String storePath, SystemMeta systemMeta ) {
        this.storeDir = storePath;
        this.systemMeta = systemMeta;
    }

    public void offer(MemTable memTable){
        mergeWaitingQueue.offer(memTable);
    }

    public boolean isMerging(){
        return mergeIsHappening  || !this.mergeWaitingQueue.isEmpty() ;
    }

    @Override
    public void run(){
        Thread.currentThread().setName("TemporalPropStore-"+(storeDir.endsWith("temporal.node.properties")?"Node":"Rel"));
        while(true){
            try{
                MemTable temp = mergeWaitingQueue.take();
                mergeIsHappening = true;
                if(!temp.isEmpty()) {
                    startMergeProcess(temp);
                }else{
//                    log.debug("empty memtable");
                }
            }catch ( IOException e ){
                e.printStackTrace();
                log.error( "error happens when dump memtable to disc", e );
            }catch ( InterruptedException e ){
                return;
            }finally{
                mergeIsHappening = false;
            }
        }
    }

    /**
     * 触发数据写入磁盘，如果需要还需要对文件进行合并
     * @param temp 需要写入磁盘的MemTable
     * @throws IOException
     */
    private void startMergeProcess( MemTable temp ) throws IOException
    {
        SeekingIterator<Slice,Slice> iterator = temp.iterator();
        Map<Integer, MemTable> tables = new HashMap<>();
        while( iterator.hasNext() ){
            Entry<Slice,Slice> entry = iterator.next();
            InternalKey key = new InternalKey( entry.getKey() );
            if(!tables.containsKey(key.getPropertyId())){
                tables.put(key.getPropertyId(), new MemTable( TableComparator.instance() ));
            }
            tables.get(key.getPropertyId()).add(entry.getKey(), entry.getValue());
        }

        List<MergeTask> taskList = new LinkedList<>();
        for(Entry<Integer, MemTable> propEntry : tables.entrySet()){
            MergeTask task = systemMeta.getStore(propEntry.getKey()).merge(propEntry.getValue());
            if(task!=null){
                task.buildNewFile();
                taskList.add(task);
            }
        }

        systemMeta.lockExclusive();
        try {
            for (MergeTask task : taskList) task.updateMetaInfo();
            systemMeta.force(new File(storeDir));
        }finally {
            systemMeta.unLockExclusive();
        }

        for(MergeTask task : taskList){
            task.deleteObsoleteFiles();
        }
    }

    // 将MemTable写入磁盘并与UnStableFile进行合并
    public static class MergeTask{
        private final File propStoreDir;
        private final MemTable mem;
        private final TableCache cache;
        private final List<Long> mergeParticipants;
        private final PropertyMetaData pMeta;

        private final List<SeekingIterator<Slice,Slice>> mergeIterators = new LinkedList<>();
        private final List<Closeable> channel2close = new LinkedList<>();
        private final List<File> files2delete = new LinkedList<>();
        private final List<String> table2evict = new LinkedList<>();

        private int entryCount;
        private int minTime;
        private int maxTime;
        private StableLevel stLevel;
        private FileChannel targetChannel;

        /**
         * @param memTable2merge 写入磁盘的MemTable
         * @param proMeta 属性元信息
         * @param cache 用来读取UnStableFile的缓存结构
         */
        public MergeTask(File propStoreDir, MemTable memTable2merge, PropertyMetaData proMeta, TableCache cache){
            this.propStoreDir = propStoreDir;
            this.mem = memTable2merge;
            this.pMeta = proMeta;
            this.cache = cache;
            this.mergeParticipants = getFile2Merge(proMeta.getUnStableFiles());

            this.mergeIterators.add(this.mem.iterator());
            if(createStableFile() && pMeta.hasStable()){
                this.mergeIterators.add(stableLatestValIter());
            }
        }

        private TableBuilder mergeInit(String targetFileName) throws IOException
        {
            boolean success;

            File targetFile = new File( propStoreDir, targetFileName );
            if( targetFile.exists() ) {
                success = targetFile.delete();
                if (!success) {
                    throw new IOException("merge init error: fail to delete exist file");
                }
            }
            success = targetFile.createNewFile();
            if (success) {
                FileOutputStream targetStream = new FileOutputStream(targetFile);
                targetChannel = targetStream.getChannel();
                this.channel2close.add( targetStream );
                this.channel2close.add( targetChannel );
                return new TableBuilder( new Options(), targetChannel, TableComparator.instance() );
            }else{
                throw new IOException("merge init error: fail to create file");
            }
        }

        private void closeUnused() throws IOException {
            for( Closeable c : channel2close ) c.close();
        }

        private void evictUnused(TableCache cache) {
            for( String filePath : table2evict ) cache.evict( filePath );
        }

        public void deleteObsoleteFiles() throws IOException {
            for( File f : files2delete ) Files.delete( f.toPath() );
        }

        private List<Long> getFile2Merge(SortedMap<Long, FileMetaData> files) {
            List<Long> toMerge = new LinkedList<>();
            for( Long fileNo : new long[]{0,1,2,3,4} ) {
                FileMetaData metaData = files.get( fileNo );
                if( null == metaData ) break;
                else toMerge.add( fileNo );
            }
            return toMerge;
        }

        private MergingIterator getDataIterator(){
            for( Long fileNumber : mergeParticipants ){
                File mergeSource = new File( propStoreDir, Filename.unStableFileName( fileNumber ) );
                Table table = cache.newTable( mergeSource.getAbsolutePath() );
                SeekingIterator<Slice,Slice> mergeIterator;
                FileBuffer filebuffer = pMeta.getUnstableBuffers( fileNumber );
                if( null != filebuffer ){
                    mergeIterator = new BufferFileAndTableIterator(
                            filebuffer.iterator(),
                            table.iterator(),
                            TableComparator.instance() );
                    channel2close.add( filebuffer );
                    files2delete.add( new File( propStoreDir, Filename.bufferFileName( fileNumber ) ) );
                }else{
                    mergeIterator = table.iterator();
                }
                mergeIterators.add( mergeIterator );

                table2evict.add( mergeSource.getAbsolutePath() );
                files2delete.add( mergeSource );
                channel2close.add( table );
            }
            return new MergingIterator( mergeIterators, TableComparator.instance() );
        }

        public boolean createStableFile(){
            return mergeParticipants.size()>=5;
        }

        public void buildNewFile() throws IOException {
            maxTime = -1;
            minTime = Integer.MAX_VALUE;
            entryCount = 0;

            String targetFileName;
            if(createStableFile()) {
                targetFileName = Filename.stableFileName(pMeta.nextStableId());
            }else{
                targetFileName = Filename.unStableFileName( mergeParticipants.size() );
            }

            TableBuilder builder = this.mergeInit(targetFileName);
            MergingIterator buildIterator = getDataIterator();
            while( buildIterator.hasNext() ){
                Entry<Slice,Slice> entry = buildIterator.next();
                InternalKey key = new InternalKey( entry.getKey() );
                if( key.getStartTime() < minTime ) minTime = key.getStartTime();
                if( key.getStartTime() > maxTime ) maxTime = key.getStartTime();
                builder.add( entry.getKey(), entry.getValue() );
                entryCount++;
            }
            builder.finish();
        }

        public void updateMetaInfo() throws IOException {
            // remove old meta
            for( Long fileNumber : mergeParticipants ){
                pMeta.delUnstable( fileNumber );
                pMeta.delUnstableBuffer( fileNumber );
            }

            // add new meta to level
            long fileNumber; int startTime;
            if(createStableFile()){
                fileNumber = pMeta.nextStableId();
                if(pMeta.hasStable()) {
                    startTime=pMeta.stMaxTime()+1;
                }else{
                    startTime=0;
                }
                FileMetaData targetMeta = new FileMetaData( fileNumber, targetChannel.size(), startTime, maxTime );
                pMeta.addStable(targetMeta);
            }else{
                fileNumber = mergeParticipants.size();
                startTime = mergeParticipantMinTime();
                assert startTime<=minTime : "ERROR: startTime > minTime";
                FileMetaData targetMeta = new FileMetaData( fileNumber, targetChannel.size(), startTime, maxTime );
                pMeta.addUnstable(targetMeta);
            }

            closeUnused();
            evictUnused(cache);
        }

        private int mergeParticipantMinTime(){
            int min = Integer.MAX_VALUE;
            for(long fileNumber : mergeParticipants){
                FileMetaData meta = pMeta.getUnStableFiles().get(fileNumber);
                if(min>meta.getSmallest()) min = meta.getSmallest();
            }
            return min;
        }

        // this should only be called when pMeta.hasStable() is true.
        private TableLatestValueIterator stableLatestValIter() {
            FileMetaData meta = pMeta.latestStableMeta();
            String filePath = Filename.stPath(propStoreDir.getAbsolutePath(), meta.getNumber());
            SeekingIterator<Slice, Slice> fileIterator = cache.newTable(filePath).iterator();
            FileBuffer buffer = pMeta.getStableBuffers( meta.getNumber() );
            if( null != buffer ){
                fileIterator = new BufferFileAndTableIterator(buffer.iterator(), fileIterator, TableComparator.instance());
            }
            return new TableLatestValueIterator(fileIterator);
        }
    }

}
